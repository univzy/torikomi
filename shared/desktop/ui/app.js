const $ = selector => document.querySelector(selector);
let state, page = 'download', limit = 50, downloading = false, inspecting = false;
let media = null, selectedFormat = null, formatKind = 'all', inspectionRevision = 0;
let album = null, selectedPhotos = new Set(), setupBusy = false, setupDownloading = false, setupProgress = null;
let historyKind = 'all', downloadEntryId = null;
const photoCache = new Map(), libraryPreviewCache = new Map(), photoQueue = [];
let activePreviews = 0;
function observePreviews(entries) {
  for (const entry of entries) {
    const frame = entry.target;
    frame.dataset.visible = String(entry.isIntersecting);
    if (entry.isIntersecting) loadPhotoPreview(frame);
    else { const image = frame.querySelector('img'); image.onload = image.onerror = null; image.removeAttribute('src'); image.hidden = true; }
  }
}
const photoObserver = new IntersectionObserver(observePreviews, { root:$('#gallery-items'), rootMargin:'160px' });
const libraryObserver = new IntersectionObserver(observePreviews, { rootMargin:'160px' });
function clearPhotoPreviews() {
  photoObserver.disconnect(); photoCache.clear(); $('#gallery-preview-retry').hidden = true;
  for (let i=photoQueue.length-1;i>=0;i--) if (photoQueue[i].command === 'gallery-preview') photoQueue.splice(i,1)[0].reject(Error('Inspection changed'));
}
function drainPhotoQueue() {
  while (activePreviews < 3 && photoQueue.length) {
    const request = photoQueue.shift();
    if (request.command === 'gallery-preview' && request.revision !== inspectionRevision) { request.reject(Error('Inspection changed')); continue; }
    activePreviews++;
    window.torikomi.call(request.command, ...request.args).then(request.resolve, request.reject).finally(() => { activePreviews--; drainPhotoQueue(); });
  }
}
async function loadPhotoPreview(frame) {
  const index = Number(frame.dataset.index), revision = Number(frame.dataset.revision);
  const entryId = frame.dataset.entryId, library = !!entryId;
  if (!library && (!album || revision !== inspectionRevision)) return;
  const cache = library ? libraryPreviewCache : photoCache, key = library ? `${entryId}:${state.files[entryId]}` : index;
  let cached = cache.get(key);
  if (!cached) {
    cached = { done:false };
    cached.promise = new Promise((resolve, reject) => { photoQueue.push({command:library ? 'library-preview' : 'gallery-preview', args:library ? [entryId] : [album.url,index],revision,resolve,reject}); drainPhotoQueue(); })
      .then(data => { if (typeof data !== 'string' || !data.startsWith('data:image/png;base64,')) throw Error('Invalid thumbnail'); cached.data=data; })
      .catch(() => { cached.error=true; })
      .then(() => {
        cached.done=true;
        // Keep a bounded thumbnail cache; offscreen images release their decoded pixels too.
        for (const [key, value] of cache) { if (cache.size <= 32) break; if (value.done) cache.delete(key); }
      });
    cache.set(key,cached);
  }
  await cached.promise;
  if (!frame.isConnected || (!library && revision !== inspectionRevision) || frame.dataset.visible !== 'true') return;
  const image = frame.querySelector('img'), placeholder = frame.querySelector('.photo-placeholder');
  const failed = () => { cached.error=true; image.hidden=true; placeholder.hidden=false; placeholder.replaceChildren(); const message=document.createElement('span'); message.textContent=t('desktop_preview_unavailable'); placeholder.append(message); $(library ? '#library-preview-retry' : '#gallery-preview-retry').hidden=false; };
  if (cached.error) { failed(); return; }
  image.onload = () => { image.hidden=false; placeholder.hidden=true; };
  image.onerror = failed;
  image.src=cached.data;
}
function createPreviewFrame(index, entryId = '') {
  const frame = document.createElement('span'); frame.className='photo-preview'; frame.dataset.index=String(index); frame.dataset.revision=String(inspectionRevision);
  if (entryId) frame.dataset.entryId=entryId;
  const image = document.createElement('img'); image.alt=index ? `${t('formats_image')} #${index}` : ''; image.decoding='async'; image.hidden=true; image.width=320; image.height=320;
  const placeholder = document.createElement('span'); placeholder.className='photo-placeholder';
  const loader = document.createElement('span'); loader.className='loader'; loader.setAttribute('aria-hidden','true');
  const loading = document.createElement('span'); loading.textContent=t('desktop_preview_loading');
  placeholder.append(loader,loading); frame.append(image,placeholder);
  return frame;
}
function language() { return TorikomiCatalog.resolveLanguage(state?.document.settings.value.language, navigator.language); }
function t(key) { return TorikomiCatalog.text(key, language()); }
function status(message, error = false) { $('#status').textContent = message; $('#status').classList.toggle('error', error); }
async function action(channel, ...args) { try { return await window.torikomi.call(channel, ...args); } catch (err) { const message = String(err?.message || err); status(message, true); if ($('#onboarding').open) $('#setup-error').textContent = message; return null; } }
function render() {
  const p = state.document.settings.value;
  const dark = p.themeMode === 'dark' || (p.themeMode === 'system' && state.isDark);
  document.documentElement.lang = language();
  document.documentElement.dir = TorikomiCatalog.languages.find(l => l.code === language()).rtl ? 'rtl' : 'ltr';
  document.body.dataset.dark = String(dark);
  document.body.classList.toggle('glass', p.glass && state.glassAvailable);
  applyPalette(document.body, p);
  document.documentElement.style.fontSize = `${16 * p.fontScale}px`;
  document.body.style.fontFamily = { system:'system-ui,sans-serif', sans:'Arial,sans-serif', serif:'Georgia,serif', mono:'ui-monospace,monospace' }[p.fontFamily];
  document.body.style.setProperty('--space', p.layout === 'compact' ? '16px' : '24px');
  document.querySelectorAll('[data-i18n]').forEach(element => { element.textContent = t(element.dataset.i18n); });
  for (const [key, value] of Object.entries(p)) {
    const input = $('#preferences').elements.namedItem(key);
    if (input) {
      if (input.tagName === 'SELECT' && ![...input.options].some(option => option.value === value)) {
        const option = document.createElement('option'); option.value = value; option.textContent = value; input.add(option);
      }
      if (input.type === 'checkbox') input.checked = value; else input.value = value;
    }
  }
  $('#font-size-value').textContent = `${Math.round(p.fontScale * 100)}%`;
  $('#follow-device').checked = p.themeMode === 'system';
  renderSwatches($('#default-themes'), TorikomiCatalog.themes.filter(t => t.group === 'default'), p, saveTheme);
  renderSwatches($('#color-themes'), TorikomiCatalog.themes.filter(t => t.group === 'color'), p, saveTheme);
  $('#preferences').elements.namedItem('glass').disabled = !state.glassAvailable;
  $('#folder-path').textContent = state.folder;
  $('#engine-path').textContent = `gallery-dl: ${state.gallery || '—'} · yt-dlp: ${state.engine || '—'} · FFmpeg: ${state.ffmpeg || '—'}`;
  $('#welcome-language').value = p.language;
  renderSetup();
  $('#current-page').textContent = t(page);
  $('#library-count').textContent = state.document.entries.filter(e => !e.deletedAt).length;
  renderEntries();
  renderFormats();
  renderGallery();
}
function renderEntries() {
  libraryObserver.disconnect();
  const query = $('#search').value.trim();
  const entries = historyEntries();
  document.querySelectorAll('[data-history-kind]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.historyKind===historyKind)));
  $('#empty-library').hidden = entries.length > 0;
  $('#empty-library').textContent = t(query ? 'noResults' : 'revision_empty_library');
  $('#entries').replaceChildren();
  entries.slice(0,limit).forEach(entry => $('#entries').append(createEntryRow(entry,entries.filter(e=>e.kind==='audio').map(e=>e.id))));
  $('#more').hidden = entries.length <= limit;
  MediaLibrary.render();
}
function historyEntries() {
  const query = $('#search').value.toLocaleLowerCase();
  return state.document.entries.filter(e => !e.deletedAt && (historyKind==='all'||e.kind===historyKind) && `${e.title} ${e.metadata?.artist||''} ${e.metadata?.album||''} ${e.url}`.toLocaleLowerCase().includes(query)).sort((a,b) => b.createdAt - a.createdAt);
}
function iconButton(label, symbol, callback) {
  const button=document.createElement('button');button.type='button';button.className='icon-button';button.title=label;button.setAttribute('aria-label',label);button.onclick=callback;
  const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');icon.classList.add('icon');icon.setAttribute('aria-hidden','true');
  const use=document.createElementNS('http://www.w3.org/2000/svg','use');use.setAttribute('href','#i-'+symbol);icon.append(use);button.append(icon);return button;
}
function createEntryRow(entry, queue = []) {
    const article = document.createElement('article'); article.className = 'entry';
    article.dataset.entryId=entry.id;
    const title = document.createElement('h2'); title.textContent = entry.title;
    const detail = document.createElement('p'); detail.className = 'small muted'; detail.textContent = [entry.metadata?.artist,entry.metadata?.album,MediaLibrary.text('media_'+(entry.kind==='image'?'photo':entry.kind==='link'?'saved_link':entry.kind)),new Date(entry.createdAt).toLocaleDateString(language())].filter(Boolean).join(' · ');
    const actions = document.createElement('div'); actions.className = 'actions';
    const button = (label, symbol, callback) => { const b=iconButton(label,symbol,callback);actions.append(b);return b; };
    button(t('download'), 'download', () => { $('#url').value = entry.url; resetInspection();downloadEntryId=entry.id;showPage('download'); $('#url').focus(); });
    if (state.files[entry.id]) {
      const play=button(MediaLibrary.text(entry.kind==='audio'||entry.kind==='video'?'media_play':'media_open'),entry.kind==='image'?'image':'play',()=>MediaLibrary.open(entry.id,queue));play.dataset.play=entry.id;
      button(MediaLibrary.text('media_open_system'),'external',()=>action('open-file',entry.id));
    }
    if(entry.kind==='audio') {
      const favorite=button(MediaLibrary.text(MediaLibrary.isFavorite(entry.id)?'media_unfavorite':'media_favorite'),'heart',()=>MediaLibrary.favorite(entry.id));favorite.dataset.favorite=entry.id;favorite.setAttribute('aria-pressed',String(MediaLibrary.isFavorite(entry.id)));
      const folder=button(MediaLibrary.text('media_add_folder'),'folder-plus',()=>MediaLibrary.chooseFolder(entry.id));folder.dataset.addFolder=entry.id;
    }
    button(t('remove'), 'trash', async () => { if (confirm(MediaLibrary.text('media_remove_confirm'))) { await action('remove-link', entry.id); } });
    const content=document.createElement('div'); content.className='entry-content'; content.append(title,detail,actions);
    if (['image','video','audio'].includes(entry.kind)) {
      const frame=createPreviewFrame(0,entry.id); article.append(frame);
      if (state.files[entry.id]) libraryObserver.observe(frame);
      else frame.querySelector('.photo-placeholder').textContent=t('desktop_preview_unavailable');
    }
    article.append(content);return article;
}
function showPage(next) {
  page = next;
  document.querySelectorAll('.page').forEach(element => { element.hidden = element.id !== page; });
  document.querySelectorAll('[data-page]').forEach(button => { if (button.dataset.page === page) button.setAttribute('aria-current','page'); else button.removeAttribute('aria-current'); });
  $('#main').focus({preventScroll:true});window.scrollTo(0,0);status('');
  $('#current-page').textContent = t(page);
  MediaLibrary.navigation();
}
document.querySelectorAll('[data-page]').forEach(button => { button.onclick = () => showPage(button.dataset.page); });
async function inspectLink(url, revision) {
  const source = new URL(url);
  const photoPost = ['tiktok.com','www.tiktok.com'].includes(source.hostname) && /^\/@[^/]+\/photo\/\d+\/?$/.test(source.pathname);
  let photos = null, photoError = null;
  try { photos = await window.torikomi.call('gallery-inspect', url); }
  catch (error) { photoError = String(error?.message || error); }
  if (revision !== inspectionRevision) return null;
  if (photoError === 'Operation cancelled') throw Error(photoError);
  if (photoPost) {
    if (photoError) throw Error(photoError);
    return {album:photos};
  }
  if (photos && !photos.items.some(item => item.kind === 'video')) return {album:photos};
  try {
    const formats = await window.torikomi.call('inspect', url);
    return revision === inspectionRevision ? {album:photos, media:formats} : null;
  } catch (error) {
    if (revision !== inspectionRevision) return null;
    const message = String(error?.message || error);
    if (photos) return {album:photos, warning:message};
    throw Error([...new Set([message,photoError].filter(Boolean))].map(text=>text.slice(0,600)).join('\n'));
  }
}
$('#download-form').onsubmit = async event => {
  event.preventDefault(); if (inspecting || downloading || !$('#url').reportValidity()) return;
  const url = $('#url').value.trim(), revision = ++inspectionRevision;
  clearPhotoPreviews();
  inspecting = true; media = null; selectedFormat = null; status(''); renderFormats();
  album = null; selectedPhotos.clear(); renderGallery();
  try {
    const result = await inspectLink(url, revision);
    if (revision === inspectionRevision && result) {
      album = result.album || null; media = result.media || null; formatKind = 'all';
      if (album) selectedPhotos = new Set(album.items.filter(item => item.kind === 'image').map(item => item.imageIndex));
      if (result.warning) status(result.warning, true);
    }
  } catch (error) {
    if (revision === inspectionRevision) status(String(error?.message || error), true);
  } finally {
    inspecting = false; renderFormats(); renderGallery();
  }
};
function resetInspection() { inspectionRevision++; clearPhotoPreviews(); media = null; album = null; selectedPhotos.clear(); selectedFormat = null; downloadEntryId=null;renderFormats(); renderGallery(); }
$('#url').oninput = resetInspection;
function bytes(value) { if (!value || value <= 0) return '—'; const units = ['B','KiB','MiB','GiB']; const power = Math.min(3, Math.floor(Math.log(value) / Math.log(1024))); return `${(value / 1024 ** power).toLocaleString(language(), { maximumFractionDigits:1 })} ${units[power]}`; }
function kindLabel(format) { return t(format.kind === 'audio' ? 'formats_audio' : format.kind === 'muxed' ? 'formats_muxed' : format.kind === 'image' ? 'formats_image' : format.kind === 'source' ? 'revision_original' : 'formats_silent'); }
function formatLabel(format) {
  return [format.height ? `${format.height}p` : kindLabel(format), format.ext?.toUpperCase(), format.fps ? `${format.fps} FPS` : format.abr ? `${format.abr} kbps` : null].filter(Boolean).join(' · ');
}
function renderFormats() {
  $('#inspect-button').disabled = inspecting || downloading;
  $('#inspection-loading').hidden = !inspecting;
  $('#download-empty').hidden = inspecting || !!media || !!album || downloading;
  $('#inspect-button span').textContent = t('revision_inspect');
  $('#format-panel').hidden = !media;
  $('#download-button').disabled = downloading || !selectedFormat || !media;
  if (!media) return;
  $('#media-title').textContent = media.title;
  $('#format-count').textContent = `${media.formats.length}`;
  document.querySelectorAll('[data-format-kind]').forEach(button => button.setAttribute('aria-pressed',String(formatKind === button.dataset.formatKind)));
  const formats = media.formats.filter(format => formatKind === 'all' || (formatKind === 'audio' ? format.kind === 'audio' : format.kind !== 'audio'));
  $('#formats').replaceChildren();
  $('#format-empty').hidden = formats.length > 0;
  for (const format of formats) {
    const row = document.createElement('label'); row.className = 'format-option';
    const radio = document.createElement('input'); radio.type = 'radio'; radio.name = 'source-format'; radio.value = format.id; radio.checked = selectedFormat === format.id;
    radio.onchange = () => { selectedFormat = format.id; updateFormatSelection(); };
    const main = document.createElement('span'); main.className = 'format-main';
    const title = document.createElement('strong'); title.textContent = formatLabel(format);
    const detail = document.createElement('span'); detail.className = 'format-detail'; detail.textContent = [kindLabel(format), `ID ${format.id}`, format.vcodec !== 'none' ? format.vcodec : null, format.acodec !== 'none' ? format.acodec : null].filter(Boolean).join(' · ');
    main.append(title,detail);
    const size = document.createElement('span'); size.className = 'format-size'; size.textContent = bytes(format.sizeBytes);
    row.append(radio,main,size); $('#formats').append(row);
  }
  updateFormatSelection();
}
function updateFormatSelection() {
  const format = media?.formats.find(format => format.id === selectedFormat);
  $('#download-button').disabled = !format || downloading;
  const previousAudio = $('#audio-track').value;
  $('#audio-track').replaceChildren();
  const none = document.createElement('option'); none.value = ''; none.textContent = t('formats_silent'); $('#audio-track').append(none);
  for (const audio of media?.formats.filter(format => format.kind === 'audio') || []) { const option = document.createElement('option'); option.value = audio.id; option.textContent = `${formatLabel(audio)} · ${audio.acodec} · ID ${audio.id}`; $('#audio-track').append(option); }
  if ([...$('#audio-track').options].some(option => option.value === previousAudio)) $('#audio-track').value = previousAudio;
  $('#audio-track-control').hidden = format?.kind !== 'video';
  $('#audio-output-control').hidden = format?.kind !== 'audio';
  $('#format-selection-note').textContent = format ? `${kindLabel(format)} · ID ${format.id}` : t('revision_choose_format');
}
document.querySelectorAll('[data-format-kind]').forEach(button => { button.onclick = () => { formatKind = button.dataset.formatKind; renderFormats(); }; });
$('#download-button').onclick = async () => {
  const format = media?.formats.find(format => format.id === selectedFormat); if (!format || downloading) return;
  const kind = format.kind === 'audio' ? 'audio' : format.kind === 'image' ? 'image' : 'video';
  const existing = state.document.entries.find(entry => entry.id === downloadEntryId && !entry.deletedAt && entry.url === media.url && entry.kind === kind);
  downloading = true; $('#progress-panel').hidden = false; $('#progress').removeAttribute('value'); $('#progress-text').textContent = ''; status(''); renderFormats();
  const result = await action('download', media.url, format.id, format.kind === 'video' ? $('#audio-track').value || null : null, format.kind === 'audio' ? $('#audio-output').value : 'original',existing?.id || null);
  downloading = false; $('#progress-panel').hidden = true;
  if (result) { state = result; render(); status(result.downloadWarning?`${MediaLibrary.text('media_partial_metadata')} ${result.downloadWarning}`:t('done')); } else renderFormats();
};
$('#save-link').onclick = async () => { if (!$('#url').reportValidity() || !$('#url').value) return; const saved = await action('save-link',$('#url').value); if (saved) status(t('saved')); };
$('#cancel').onclick = () => action('cancel');
function renderGallery() {
  photoObserver.disconnect(); $('#gallery-items').replaceChildren();
  $('#gallery-panel').hidden = !album;
  $('#gallery-download').disabled = downloading || !selectedPhotos.size;
  $('#gallery-all').disabled = downloading; $('#gallery-none').disabled = downloading;
  if (!album) return;
  const photos = album.items.filter(item => item.kind === 'image');
  $('#gallery-count').textContent = String(photos.length);
  $('#gallery-selected').textContent = String(selectedPhotos.size);
  for (const item of photos) {
    const row = document.createElement('label'); row.className = 'format-option gallery-option';
    const input = document.createElement('input'); input.type = 'checkbox'; input.value = String(item.imageIndex); input.checked = selectedPhotos.has(item.imageIndex); input.disabled = downloading;
    input.onchange = () => { if (input.checked) selectedPhotos.add(item.imageIndex); else selectedPhotos.delete(item.imageIndex); $('#gallery-selected').textContent = String(selectedPhotos.size); $('#gallery-download').disabled = !selectedPhotos.size; };
    input.setAttribute('aria-label', `${t('desktop_album_select')} #${item.imageIndex}`);
    const frame = createPreviewFrame(item.imageIndex);
    const main = document.createElement('span'); main.className = 'format-main';
    const title = document.createElement('strong'); title.textContent = item.title; title.title=item.title;
    const detail = document.createElement('span'); detail.className = 'format-detail'; detail.textContent = [`#${item.imageIndex}`, item.ext.toUpperCase(), item.width && item.height ? `${item.width} × ${item.height}` : null].filter(Boolean).join(' · ');
    main.append(title, detail); row.append(input, frame, main); $('#gallery-items').append(row); photoObserver.observe(frame);
  }
}
$('#gallery-all').onclick = () => { selectedPhotos = new Set(album.items.filter(item => item.kind === 'image').map(item => item.imageIndex)); renderGallery(); };
$('#gallery-none').onclick = () => { selectedPhotos.clear(); renderGallery(); };
$('#gallery-preview-retry').onclick = () => { for (const [index,cached] of photoCache) if (cached.error) photoCache.delete(index); $('#gallery-preview-retry').hidden=true; renderGallery(); };
$('#library-preview-retry').onclick = () => { for (const [key,cached] of libraryPreviewCache) if (cached.error) libraryPreviewCache.delete(key); $('#library-preview-retry').hidden=true; renderEntries(); };
$('#gallery-download').onclick = async () => {
  if (!album || downloading || !selectedPhotos.size) return;
  downloading = true; $('#progress-panel').hidden = false; $('#progress').removeAttribute('value'); $('#progress-text').textContent = ''; status(''); renderFormats(); renderGallery();
  const result = await action('gallery-download', album.url, [...selectedPhotos].sort((a,b) => a-b));
  downloading = false; $('#progress-panel').hidden = true;
  if (result) { state = result; render(); status(t('done')); } else { renderFormats(); renderGallery(); }
};
$('#open-folder').onclick = async () => { const result = await action('open-folder'); if (result) status(result,true); };
$('#search').oninput = () => { limit = 50; renderEntries(); };
$('#more').onclick = () => { limit += 50; renderEntries(); };
$('#preferences').onchange = async () => {
  const form = $('#preferences'); const value = { ...state.document.settings.value };
  for (const key of Object.keys(value)) { const input = form.elements.namedItem(key); if (input) value[key] = input.type === 'checkbox' ? input.checked : input.type === 'range' ? Number(input.value) : input.value; }
  const result = await action('settings',value); if (result) { state = result; render(); }
};
$('#preferences').onsubmit = event => event.preventDefault();
function applyPalette(element, preferences) {
  const dark = preferences.themeMode === 'dark' || (preferences.themeMode === 'system' && state.isDark);
  const colors = TorikomiCatalog.theme(preferences.themeColor)[dark ? 'dark' : 'light'];
  for (const [token, key] of Object.entries({bg:'background',panel:'panel',ink:'foreground',muted:'muted',line:'border',accent:'accent'})) element.style.setProperty(`--${token}`,colors[key]);
  element.style.setProperty('--gradient-start', colors.gradientStart);
  element.style.setProperty('--gradient-end', colors.gradientEnd);
  element.style.setProperty('--accent-soft','color-mix(in srgb,var(--accent) 14%,var(--panel))');
  element.style.colorScheme = dark ? 'dark' : 'light';
}
function renderSwatches(container, presets, preferences, select) {
  const focused = container.contains(document.activeElement) ? document.activeElement.dataset.theme : null;
  container.replaceChildren();
  const selected = presets.some(p => p.id === preferences.themeColor) ? preferences.themeColor : presets[0].id;
  for (const preset of presets) {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'swatch'; button.dataset.theme = preset.id;
    const checked = preferences.themeColor === preset.id;
    const label = preset.group === 'default' ? t(`theme_${preset.id === 'neutral' ? 'light' : preset.id === 'slate' ? 'dark' : preset.id}`) : preset.name;
    button.setAttribute('role','radio'); button.setAttribute('aria-label',label); button.setAttribute('aria-checked',String(checked)); button.title = `${label}${checked ? ` · ${t('theme_selected')}` : ''}`;
    button.tabIndex = preset.id === selected ? 0 : -1;
    button.style.setProperty('--start',preset.swatch[0]); button.style.setProperty('--end',preset.swatch[1]);
    button.onclick = () => select({ ...preferences, themeColor:preset.id, themeMode:preset.group === 'default' ? preset.mode : preferences.themeMode });
    button.onkeydown = event => {
      const keys = ['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End']; if (!keys.includes(event.key)) return;
      event.preventDefault(); const buttons = [...container.children]; const index = buttons.indexOf(button);
      const backwards = event.key === 'ArrowUp' || event.key === (document.documentElement.dir === 'rtl' ? 'ArrowRight' : 'ArrowLeft');
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (backwards ? -1 : 1) + buttons.length) % buttons.length;
      buttons[next].focus(); buttons[next].click();
    };
    container.append(button);
  }
  if (focused) container.querySelector(`[data-theme="${focused}"]`)?.focus();
}
async function saveTheme(preferences) { const result = await action('settings',{ ...state.document.settings.value, themeColor:preferences.themeColor, themeMode:preferences.themeMode }); if (result) { state = result; render(); } }
$('#follow-device').onchange = event => {
  event.stopPropagation(); saveTheme({ ...state.document.settings.value, themeMode:event.target.checked ? 'system' : state.isDark ? 'dark' : 'light' });
};
const systemOption = document.createElement('option'); systemOption.value = 'system'; systemOption.dataset.i18n = 'system'; $('#language-options').append(systemOption);
for (const language of TorikomiCatalog.languages) { const option = document.createElement('option'); option.value = language.code; option.textContent = language.name; $('#language-options').append(option); }
$('#welcome-language').append(...[...$('#language-options').options].map(option => option.cloneNode(true)));
$('#welcome-language').onchange = async event => { const result = await action('settings', { ...state.document.settings.value, language:event.target.value }); if (result) { state = result; render(); } };
function setupStep(step) {
  $('#onboarding-intro').hidden = step !== 1; $('#onboarding-setup').hidden = step !== 2;
  $('#onboarding-step').textContent = `${step} / 2`;
  $('#onboarding').setAttribute('aria-labelledby', step === 1 ? 'onboarding-title' : 'setup-title');
  $('#onboarding').setAttribute('aria-describedby', step === 1 ? 'onboarding-note' : 'setup-note setup-progress');
  (step === 1 ? $('#onboarding-next') : $('#setup-auto')).focus();
}
function renderSetup() {
  const ready = !!(state.engine && state.gallery && state.ffmpeg);
  const available = state.downloadAvailable ?? state.bundleAvailable;
  $('#setup-auto').disabled = setupBusy || !available;
  $('#setup-finish').disabled = setupBusy || !ready;
  $('#setup-back').disabled = setupBusy;
  $('#setup-unavailable').hidden = !!available;
  $('#setup-cancel').hidden = !setupDownloading;
  $('#setup-meter').hidden = !setupDownloading;
  for (const kind of ['engine','gallery','ffmpeg']) $('#setup-' + kind + '-path').textContent = state[kind] || '—';
  $('#onboarding').querySelectorAll('[data-choose]').forEach(button => { button.disabled = setupBusy; });
  $('#setup-progress').textContent = setupBusy ? t('desktop_setup_busy') : ready ? t('desktop_setup_ready') : '';
  if (setupDownloading && setupProgress) {
    const percent = setupProgress.total > 0 ? Math.min(100, Math.round(setupProgress.received / setupProgress.total * 100)) : null;
    $('#setup-progress').textContent = `${t('desktop_setup_busy')} ${setupProgress.tool}${percent === null ? '' : ` · ${percent}%`}`;
    if (percent === null) $('#setup-meter').removeAttribute('value');
    else $('#setup-meter').value = percent;
  }
}
$('#onboarding-next').onclick = () => setupStep(2);
$('#setup-back').onclick = () => { if (state.setupComplete) $('#onboarding').close(); else setupStep(1); };
$('#onboarding').oncancel = event => { if (setupBusy || !state.setupComplete) event.preventDefault(); };
$('#manage-engines').onclick = () => { $('#setup-error').textContent = ''; setupStep(2); $('#onboarding').showModal(); };
async function setupAction(command) {
  setupBusy = true; setupDownloading = command === 'setup-auto'; setupProgress = null;
  $('#setup-cancel').disabled = false; $('#setup-meter').removeAttribute('value');
  $('#setup-error').textContent = ''; renderSetup();
  const result = await action(command);
  setupBusy = false; setupDownloading = false; setupProgress = null;
  if (result) { state = result; render(); if (command === 'setup-complete') $('#onboarding').close(); }
  renderSetup();
}
$('#setup-auto').onclick = () => setupAction('setup-auto');
$('#setup-cancel').onclick = async () => { $('#setup-cancel').disabled = true; await action('cancel'); };
$('#setup-finish').onclick = () => setupAction('setup-complete');
let previewPreferences;
function renderPreview() {
  applyPalette($('#preview-sample'), previewPreferences);
  renderSwatches($('#preview-swatches'), TorikomiCatalog.themes, previewPreferences, value => { previewPreferences = value; renderPreview(); });
}
$('#preview-theme').onclick = () => { previewPreferences = { ...state.document.settings.value }; renderPreview(); $('#theme-preview').showModal(); };
$('#close-preview').onclick = () => $('#theme-preview').close();
$('#apply-preview').onclick = async () => { await saveTheme(previewPreferences); $('#theme-preview').close(); };
document.querySelectorAll('[data-choose]').forEach(button => { button.onclick = async () => { const inSetup = $('#onboarding').open; if (inSetup) { setupBusy = true; $('#setup-error').textContent = ''; renderSetup(); } const result = await action('choose', button.dataset.choose); setupBusy = false; if (result) { state = result; render(); } renderSetup(); }; });
$('#backup-export').onclick = async () => { $('#backup-export').disabled = true; const result = await action('backup-export'); $('#backup-export').disabled = false; if (result && !result.cancelled) status(t('backup_exported')); };
$('#backup-import').onclick = async () => { $('#backup-import').disabled = true; const result = await action('backup-import-preview'); $('#backup-import').disabled = false; if (result && !result.cancelled) { $('#backup-summary').textContent = String(result.count); $('#backup-preview').showModal(); } };
$('#apply-import').onclick = async () => { $('#apply-import').disabled = true; const result = await action('backup-import-apply'); $('#apply-import').disabled = false; if (result) { state = result; render(); $('#backup-preview').close(); status(t('backup_imported')); } };
async function cancelImport() { await action('backup-import-cancel'); $('#backup-preview').close(); }
$('#cancel-import').onclick = cancelImport;
$('#backup-preview').oncancel = event => { event.preventDefault(); cancelImport(); };
MediaLibrary.init();
Promise.all([
  window.torikomi.onState(value => { state = value; render(); }),
  window.torikomi.onProgress(value => { const match = value.text.match(/progress:\s*([\d.]+)%/); if (match) $('#progress').value = Number(match[1]); $('#progress-text').textContent = value.text.trim(); }),
  window.torikomi.onSetupProgress(value => { setupProgress = value; if (setupDownloading) renderSetup(); }),
]).then(() => action('load')).then(value => { if (value) { state = value; render(); if (!state.setupComplete) { setupStep(1); $('#onboarding').showModal(); } } }).catch(error => status(String(error),true));
