const MediaLibrary = (() => {
  const labels = {
    "media_history":"History","media_history_title":"Your downloads, ready to revisit.","media_favorites":"Favorites","media_favorites_title":"Keep your favorites together.","media_all":"All","media_new_folder":"New folder","media_favorites_note":"Favorite an audio from History, then arrange it into your own folders.","media_back_folders":"All folders","media_play":"Play","media_pause":"Pause","media_previous":"Previous track","media_next":"Next track","media_shuffle":"Shuffle","media_repeat":"Repeat","media_repeat_one":"Repeat one","media_volume":"Volume","media_rename":"Rename folder","media_delete_folder":"Remove folder","media_empty_folder":"No audio in this folder yet. Add audio from History.","media_all_favorites":"All favorite audio","media_favorite":"Favorite","media_unfavorite":"Remove favorite","media_add_folder":"Add to folder","media_remove_folder_track":"Remove from this folder","media_open_system":"Open in system app","media_close":"Close","media_now_playing":"Now playing","media_no_artwork":"No artwork available","media_unknown_artist":"Artist unavailable","media_codec_error":"This format could not be displayed. You can open the original in your system app.","media_prepare_playback":"Prepare playback copy","media_preparing":"Preparing a playback copy. Your original file is kept.","media_zoom":"Zoom","media_folder_name":"Folder name","media_save":"Save","media_folder_note":"Collections reference your downloads. Your media files stay in place.","media_done":"Done","media_delete_folder_confirm":"Remove this folder? Your favorites and downloaded files will be kept.","media_remove_confirm":"Remove this History entry and its favorite references? The downloaded file will be kept.","media_engine_updates":"Engine updates","media_auto_updates":"Update engines automatically","media_update_note":"Checks every 24 hours. Your manually selected tools remain under your control.","media_check_updates":"Check now","media_managed":"Managed by Torikomi","media_manual":"User managed","media_update_idle":"Ready to check for updates.","media_update_checking":"Checking for engine updates…","media_update_downloading":"Downloading and verifying engines…","media_update_ready":"Update verified. Waiting for the current operation to finish.","media_update_updated":"Engine update installed.","media_update_manual":"Selected tools are user managed.","media_update_no_feed":"No engine update package is published yet. Your installed tools are kept.","media_update_up_to_date":"Your managed engines are up to date.","media_update_error":"Could not update engines. Your installed tools are kept.","media_last_checked":"Last checked","media_seek":"Playback position","media_saved_link":"Saved link","media_photo":"Photo","media_audio":"Audio","media_video":"Video","media_open":"Open","media_partial_metadata":"Audio downloaded. Some metadata or artwork could not be added."
  };
  const text = key => TorikomiCatalog.messages[language()]?.[key] || labels[key] || key;
  let folderId=null, editingFolder=null, collectionEntry=null, newFolderEntry=null;
  let currentId=null, currentInfo=null, queue=[], previous=[], revision=0, viewerRevision=0, shuffle=false, repeat='off', viewerId=null, coverKey=null, updateBusy=false, copyBusy=false, audioUrl=null, viewerUrl=null, returnPage='library';
  let playerKey=null, lastStartedId=null, lastVolume=100, shuffled=[];
  function release(url) {if(url)window.torikomi.call('media-release',url).catch(()=>{});}
  const audio = () => $('#audio-player');
  const members = () => state.document.memberships || [];
  const folders = () => (state.document.folders || []).filter(f=>!f.deletedAt);
  const isFavorite = id => members().some(m=>m.folderId==='favorites'&&m.entryId===id&&!m.deletedAt);
  const inFolder = (folder,id) => members().some(m=>m.folderId===folder&&m.entryId===id&&!m.deletedAt);
  const getEntry = id => state.document.entries.find(e=>e.id===id&&!e.deletedAt);
  const folderTracks = id => state.document.entries.filter(e=>!e.deletedAt&&e.kind==='audio'&&isFavorite(e.id)&&(id==='favorites'||inFolder(id,e.id)));
  const clock = value => { const n=Math.max(0,Math.floor(Number.isFinite(value)?value:0));return `${Math.floor(n/60)}:${String(n%60).padStart(2,'0')}`; };
  function restorePlayer() {
    const key='torikomi.player.'+state.deviceId;if(playerKey===key)return;
    playerKey=key;lastVolume=100;
    try {
      const raw=localStorage.getItem(key);if(!raw||raw.length>65536)return;
      const saved=JSON.parse(raw);if(!saved||typeof saved!=='object')return;
      if(Number.isFinite(saved.volume)&&saved.volume>=0&&saved.volume<=100)audio().volume=(saved.volume/100)**2;
      audio().muted=saved.muted===true;
      if(Number.isFinite(saved.lastVolume)&&saved.lastVolume>0&&saved.lastVolume<=100)lastVolume=saved.lastVolume;
      if(Object.prototype.hasOwnProperty.call(saved,'history'))savePlayer();
    } catch { /* Playback stays available when local storage is unavailable. */ }
  }
  function savePlayer() {
    if(!playerKey)return;
    try {localStorage.setItem(playerKey,JSON.stringify({volume:Math.sqrt(audio().volume)*100,muted:audio().muted,lastVolume}));} catch {}
  }
  function setVolume(value) {
    if(!Number.isFinite(value))return;
    const percent=Math.max(0,Math.min(100,value));if(percent>0)lastVolume=percent;
    audio().volume=(percent/100)**2;audio().muted=percent===0;savePlayer();refreshPlayer();
  }
  function shuffleQueue() {
    shuffled=queue.filter(id=>id!==currentId);
    for(let i=shuffled.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[shuffled[i],shuffled[j]]=[shuffled[j],shuffled[i]];}
    if(currentId&&queue.includes(currentId))shuffled.unshift(currentId);
  }
  const playableQueue=()=>(shuffle?shuffled:queue).filter(id=>getEntry(id)&&state.files[id]);
  async function change(command,...args) { const result=await action(command,...args);if(result?.document){state=result;render();}return result; }
  async function favorite(id) { return change('favorite',id,!isFavorite(id)); }
  function navigation() {
    if(['library','favorites','now-playing'].includes(page))$('#current-page').textContent=text(page==='library'?'media_history':page==='favorites'?'media_favorites':'media_now_playing');
    const mini=!!getEntry(currentId)&&page!=='now-playing';
    $('#player-bar').hidden=!mini;document.body.classList.toggle('has-player',mini);
  }
  function showPlayer() {if(page!=='now-playing')returnPage=page;showPage('now-playing');}
  function back() {showPage(returnPage);}
  function renderQueue() {
    const focusId=document.activeElement.closest('.queue-track')?.dataset.queueId;
    const available=playableQueue(),index=available.indexOf(currentId);
    const upcoming=[...available.slice(index+1),...(repeat==='all'?available.slice(0,index):[])].filter(id=>id!==currentId).slice(0,50);
    const current=getEntry(currentId)?.kind==='audio'&&state.files[currentId]?[currentId]:[];
    $('#player-queue').replaceChildren(...[...current,...upcoming].map(trackRow));
    $('#player-queue-empty').hidden=!!upcoming.length;
    refreshTrackRows();observe();
    if(focusId){const row=[...$('#player-queue').children].find(row=>row.dataset.queueId===focusId);row?.focus({preventScroll:true});}
  }
  function trackRow(id) {
    const entry=getEntry(id),row=document.createElement('button');row.type='button';row.dataset.queueId=id;row.className='queue-track';
    const details=document.createElement('span');details.className='queue-track-details';
    const name=document.createElement('strong');name.textContent=entry.title;name.title=entry.title;
    const artist=document.createElement('small');artist.className='muted';artist.textContent=entry.metadata?.artist||text('media_unknown_artist');artist.title=artist.textContent;details.append(name,artist);
    const time=document.createElement('small');time.className='muted track-time';
    time.textContent=entry.metadata?.duration?clock(entry.metadata.duration):'';
    const play=$('#now-playing [data-player="play"] .icon').cloneNode(true);
    row.append(createPreviewFrame(0,id),details,time,play);row.onclick=()=>{if(id===currentId){if(audio().paused)playCurrent();else audio().pause();}else open(id);};return row;
  }
  function refreshTrackRows() {
    document.querySelectorAll('.queue-track').forEach(row=>{
      const id=row.dataset.queueId,entry=getEntry(id),current=id===currentId,playing=current&&!audio().paused;
      if(current)row.setAttribute('aria-current','true');else row.removeAttribute('aria-current');
      const label=text(playing?'media_pause':'media_play')+': '+(entry?.title||'')+' · '+(entry?.metadata?.artist||text('media_unknown_artist'));
      row.title=(current?text('media_now_playing')+': ':'')+label;row.setAttribute('aria-label',row.title);
      row.querySelector(':scope>.icon use').setAttribute('href',playing?'#i-pause':'#i-play');
    });
  }

  function observe() {
    libraryObserver.disconnect();
    document.querySelectorAll('#entries .photo-preview,#favorites .photo-preview,#player-queue .photo-preview').forEach(frame=>{if(state.files[frame.dataset.entryId])libraryObserver.observe(frame);});
  }
  function renderFavorites() {
    const chosen=folderId==='favorites'?{id:folderId,name:text('media_all_favorites')}:folders().find(f=>f.id===folderId);
    if(!chosen)folderId=null;
    $('#favorite-title').textContent=chosen?.name||text('media_favorites_title');
    $('#favorites-note').hidden=!!chosen;$('#favorite-folder-actions').hidden=!chosen;
    $('#favorite-rename').hidden=folderId==='favorites';$('#favorite-delete').hidden=folderId==='favorites';
    $('#favorite-folders').hidden=!!chosen;$('#favorite-folders').replaceChildren();$('#favorite-tracks').replaceChildren();
    if(chosen) {
      const tracks=folderTracks(chosen.id);$('#favorite-empty').hidden=tracks.length>0;$('#favorite-play').disabled=!tracks.some(e=>state.files[e.id]);
      for(const entry of tracks) {
        const row=createEntryRow(entry,tracks.map(e=>e.id));
        if(chosen.id!=='favorites')row.querySelector('.actions').append(iconButton(text('media_remove_folder_track'),'folder-minus',()=>change('folder-track-remove',chosen.id,entry.id)));
        $('#favorite-tracks').append(row);
      }
    } else {
      $('#favorite-empty').hidden=true;
      for(const folder of [{id:'favorites',name:text('media_all_favorites')},...folders()]) {
        const button=document.createElement('button');button.type='button';button.className='favorite-folder';button.dataset.folder=folder.id;
        const tracks=folderTracks(folder.id),local=tracks.find(e=>state.files[e.id]);
        if(local)button.append(createPreviewFrame(0,local.id));
        else {const icon=document.createElement('span');icon.className='folder-art';icon.textContent='♫';icon.setAttribute('aria-hidden','true');button.append(icon);}
        const name=document.createElement('strong');name.textContent=folder.name;
        const count=document.createElement('span');count.className='small muted';count.textContent=`${tracks.length} · ${text('media_audio')}`;
        button.append(name,count);button.onclick=()=>{folderId=folder.id;renderFavorites();observe();};$('#favorite-folders').append(button);
      }
    }
  }
  function openFolderDialog(folder=null,entry=null) {
    editingFolder=folder?.id||null;newFolderEntry=entry;
    $('#folder-dialog-title').textContent=text(folder?'media_rename':'media_new_folder');$('#folder-name').value=folder?.name||'';$('#folder-error').textContent='';
    $('#folder-dialog').showModal();$('#folder-name').focus();
  }
  function chooseFolder(id) {
    collectionEntry=id;$('#collection-options').replaceChildren();
    for(const folder of folders()) {
      const label=document.createElement('label');label.className='toggle';
      const name=document.createElement('span');name.textContent=folder.name;
      const input=document.createElement('input');input.type='checkbox';input.checked=inFolder(folder.id,id);
      input.onchange=async()=>{input.disabled=true;await change(input.checked?'folder-add':'folder-track-remove',folder.id,id);input.checked=inFolder(folder.id,id);input.disabled=false;};
      label.append(name,input);$('#collection-options').append(label);
    }
    $('#collection-dialog').showModal();
  }
  function refreshPlayer() {
    const entry=getEntry(currentId),playing=!!entry&&!audio().paused;
    const available=playableQueue(),atEnd=available.indexOf(currentId)>=available.length-1;
    if(!entry&&currentId){revision++;currentId=null;audio().pause();audio().removeAttribute('src');audio().load();release(audioUrl);audioUrl=null;}
    if(!entry&&page==='now-playing')back();
    const metadata={...entry?.metadata,...currentInfo?.metadata};
    for(const id of ['player-title','now-playing-title']) $("#"+id).textContent=entry?.title||'';
    for(const id of ['player-artist','now-playing-artist']) $("#"+id).textContent=metadata.artist||text('media_unknown_artist');
    $('#now-playing-album').textContent=[metadata.album,metadata.year].filter(Boolean).join(' · ');
    document.querySelectorAll('[data-current-favorite]').forEach(button=>{
      button.setAttribute('aria-pressed',String(!!entry&&isFavorite(entry.id)));
      button.title=text(entry&&isFavorite(entry.id)?'media_unfavorite':'media_favorite');button.setAttribute('aria-label',button.title);
    });
    document.querySelectorAll('[data-player]').forEach(button=>{
      const kind=button.dataset.player;button.title=text('media_'+(kind==='repeat'&&repeat==='one'?'repeat_one':kind));button.setAttribute('aria-label',button.title);
      if(kind==='play')button.hidden=playing;if(kind==='pause')button.hidden=!playing;
      if(kind==='shuffle'||kind==='repeat')button.setAttribute('aria-pressed',String(kind==='shuffle'?shuffle:repeat!=='off'));
      if(kind==='repeat')button.querySelector('use').setAttribute('href',repeat==='one'?'#i-repeat-one':'#i-repeat');
      button.disabled=!entry||copyBusy||(kind==='next'&&atEnd&&repeat!=='all');
    });
    const duration=Number.isFinite(audio().duration)?audio().duration:Number(currentInfo?.duration||metadata.duration||0);
    document.querySelectorAll('[data-player-seek]').forEach(input=>{input.max=String(duration);input.value=String(audio().currentTime||0);input.disabled=!duration;input.setAttribute('aria-label',text('media_seek'));});
    document.querySelectorAll('.player-elapsed').forEach(span=>span.textContent=clock(audio().currentTime));
    document.querySelectorAll('.player-duration').forEach(span=>span.textContent=clock(duration));
    const volume=Math.round(audio().muted?0:Math.sqrt(audio().volume)*100),muted=audio().muted||audio().volume===0;
    document.querySelectorAll('[data-player-volume]').forEach(input=>{input.value=String(volume);input.setAttribute('aria-label',text('media_volume'));input.setAttribute('aria-valuetext',volume+'%');input.style.setProperty('--range-fill',volume+'%');});
    document.querySelectorAll('[data-volume-value]').forEach(output=>output.textContent=volume+'%');
    document.querySelectorAll('[data-player-mute]').forEach(button=>{button.title=text(muted?'media_unmute':'media_mute');button.setAttribute('aria-label',button.title);button.setAttribute('aria-pressed',String(muted));button.querySelector('use').setAttribute('href',muted?'#i-volume-off':'#i-volume');});
    $('#show-now-playing').setAttribute('aria-label',`${text('media_now_playing')}: ${entry?.title||''}`);
    if(entry){const key=entry.id+':'+state.files[entry.id];if(key!==coverKey){coverKey=key;loadCover(entry.id,key);}}
    refreshTrackRows();navigation();
  }
  async function loadCover(id,key) {
    for(const target of ['#player-cover','#now-playing-cover']) {$(target).hidden=true;$(target).removeAttribute('src');}
    $('#now-playing-cover-empty').hidden=false;
    try {
      const data=await window.torikomi.call('library-preview',id);
      if(key!==coverKey||typeof data!=='string'||!data.startsWith('data:image/png;base64,'))return;
      for(const target of ['#player-cover','#now-playing-cover']) {$(target).src=data;$(target).hidden=false;}
      $('#now-playing-cover-empty').hidden=true;
    } catch { /* Missing artwork is a normal state for untagged audio. */ }
  }
  function playbackError() {
    if(!currentId||!audio().getAttribute('src'))return;
    $('#player-error').textContent=text('media_codec_error');$('#prepare-playback').hidden=false;
    if(!document.querySelector('dialog[open]'))showPlayer();
  }
  async function playCurrent(backwards=false) {
    if(!audio().getAttribute('src')){if(currentId)await open(currentId,null,false,false);return;}
    const id=currentId,ticket=revision;
    try {
      await audio().play();if(id!==currentId||ticket!==revision)return;
      $('#player-error').textContent='';$('#prepare-playback').hidden=true;
      if(lastStartedId!==id){
        if(!backwards&&lastStartedId){previous.push(lastStartedId);if(previous.length>50)previous.shift();}
        lastStartedId=id;
      }
    } catch(error) {if(error.name!=='AbortError')playbackError();}
    refreshPlayer();
  }
  async function open(id,ids=null,compatible=false,navigate=true,backwards=false) {
    const entry=getEntry(id);if(!entry||!state.files[id])return;
    if(entry.kind!=='audio')return openViewer(entry);
    viewerRevision++;const ticket=++revision;audio().pause();audio().removeAttribute('src');audio().load();release(audioUrl);audioUrl=null;
    if(ids) {queue=ids.filter(key=>getEntry(key)?.kind==='audio');}
    else if(!queue.includes(id))queue=[id];
    currentId=id;currentInfo=null;$('#player-error').textContent='';$('#prepare-playback').hidden=true;
    if(shuffle&&(ids||!shuffled.includes(id)))shuffleQueue();
    if(navigate)showPlayer();refreshPlayer();renderQueue();$('#now-playing').setAttribute('aria-busy','true');
    const info=await action(compatible?'media-compatible':'media-open',id);
    if(ticket!==revision){release(info?.url);return;}
    $('#now-playing').setAttribute('aria-busy','false');
    if(!info){$('#player-error').textContent=$('#status').textContent;refreshPlayer();return;}
    currentInfo=info;audioUrl=info.url;audio().src=info.url;audio().load();await playCurrent(backwards);
  }
  async function move(direction) {
    const available=playableQueue();if(!available.length)return;
    if(direction<0&&audio().currentTime>3){audio().currentTime=0;return;}
    let id;
    if(direction<0){while(previous.length&&!id){const candidate=previous.pop();if(candidate!==currentId&&getEntry(candidate)?.kind==='audio'&&state.files[candidate])id=candidate;}}
    if(!id){const index=available.indexOf(currentId)+direction;id=direction<0?available[Math.max(0,index)]:available[index]||(repeat==='all'?available[0]:null);}
    if(!id)return;await open(id,null,false,false,direction<0);
  }
  async function openViewer(entry) {
    const ticket=++viewerRevision;
    if(entry.kind==='video'){revision++;$('#now-playing').setAttribute('aria-busy','false');audio().pause();}
    const info=await action('media-open',entry.id);if(!info)return;
    if(ticket!==viewerRevision){release(info.url);return;}
    release(viewerUrl);
    viewerId=entry.id;viewerUrl=info.url;$('#viewer-title').textContent=entry.title;$('#viewer-error').hidden=true;
    const photo=entry.kind==='image';$('#viewer-image').hidden=!photo;$('#viewer-video').hidden=photo;$('#viewer-zoom-label').hidden=!photo;
    $('#viewer-image').removeAttribute('src');$('#viewer-video').pause();$('#viewer-video').removeAttribute('src');$('#viewer-video').load();
    const target=$(photo?'#viewer-image':'#viewer-video');target.onerror=()=>$('#viewer-error').hidden=false;
    if(photo){target.alt=entry.title;target.style.width='';target.style.maxHeight='';$('#viewer-zoom').value='100';}
    else audio().pause();
    target.src=info.url;if(!$('#media-viewer').open)$('#media-viewer').showModal();if(!photo)target.play().catch(()=>{});
  }
  function renderUpdates() {
    const updates=state.engineUpdates||{automatic:true,state:'idle',tools:{}};
    $('#auto-engine-updates').checked=updates.automatic;$('#engine-versions').replaceChildren();
    for(const [name,tool] of Object.entries(updates.tools||{})) {const title=document.createElement('dt'),value=document.createElement('dd');title.textContent=name;value.textContent=`${tool.version||'?'} · ${text(tool.managed?'media_managed':'media_manual')}`;$('#engine-versions').append(title,value);}
    $('#engine-update-status').textContent=[text('media_update_'+updates.state),updates.error,updates.lastChecked?`${text('media_last_checked')}: ${new Date(updates.lastChecked).toLocaleString(language())}`:''].filter(Boolean).join(' ');
    $('#check-engine-updates').disabled=updateBusy||['checking','downloading'].includes(updates.state);
  }
  function renderMedia() {
    restorePlayer();
    document.querySelectorAll('[data-media-i18n]').forEach(node=>node.textContent=text(node.dataset.mediaI18n));
    document.querySelectorAll('[data-icon-label]').forEach(button=>{button.title=text(button.dataset.iconLabel);if(button.title===button.dataset.iconLabel)button.title=t(button.dataset.iconLabel);button.setAttribute('aria-label',button.title);});
    renderFavorites();observe();refreshPlayer();renderQueue();renderUpdates();
  }
  function init() {
    const bar=$('#player-bar');
    new ResizeObserver(()=>document.documentElement.style.setProperty('--player-height',`${bar.getBoundingClientRect().height}px`)).observe(bar);
    document.querySelectorAll('[data-history-kind]').forEach(button=>button.onclick=()=>{historyKind=button.dataset.historyKind;limit=50;renderEntries();});
    $('#new-favorite-folder').onclick=()=>openFolderDialog();
    $('#favorite-back').onclick=()=>{folderId=null;renderFavorites();observe();};
    $('#favorite-play').onclick=()=>{const tracks=folderTracks(folderId).filter(e=>state.files[e.id]);if(tracks.length)open(tracks[0].id,tracks.map(e=>e.id));};
    $('#favorite-rename').onclick=()=>openFolderDialog(folders().find(f=>f.id===folderId));
    $('#favorite-delete').onclick=async()=>{if(confirm(text('media_delete_folder_confirm'))){await change('folder-remove',folderId);folderId=null;renderMedia();}};
    $('#folder-form').onsubmit=async event=>{event.preventDefault();if(!$('#folder-name').reportValidity())return;$('#save-folder').disabled=true;const before=new Set(folders().map(f=>f.id));const result=await change(editingFolder?'folder-rename':'folder-create',...(editingFolder?[editingFolder,$('#folder-name').value.trim()]:[$('#folder-name').value.trim()]));$('#save-folder').disabled=false;if(result){if(newFolderEntry){const folder=folders().find(f=>!before.has(f.id));if(folder)await change('folder-add',folder.id,newFolderEntry);}$('#folder-dialog').close();}else $('#folder-error').textContent=$('#status').textContent;};
    $('#close-collections').onclick=()=>$('#collection-dialog').close();
    $('#collection-new-folder').onclick=()=>{$('#collection-dialog').close();openFolderDialog(null,collectionEntry);};
    document.querySelectorAll('[data-close-dialog]').forEach(button=>button.onclick=()=>$('#'+button.dataset.closeDialog).close());
    $('#media-viewer').addEventListener('close',()=>{viewerRevision++;$('#viewer-video').pause();$('#viewer-video').removeAttribute('src');$('#viewer-video').load();$('#viewer-image').removeAttribute('src');release(viewerUrl);viewerUrl=null;viewerId=null;});
    $('#viewer-system-open').onclick=()=>viewerId&&action('open-file',viewerId);
    $('#viewer-zoom').oninput=event=>{const zoom=Number(event.target.value);$('#viewer-image').style.width=zoom>100?`${zoom}%`:'';$('#viewer-image').style.maxHeight=zoom>100?'none':'';};
    $('#show-now-playing').onclick=showPlayer;$('#now-playing-back').onclick=back;
    document.addEventListener('keydown',event=>{if(event.key==='Escape'&&page==='now-playing'&&!document.querySelector('dialog[open]'))back();});
    document.querySelectorAll('[data-current-favorite]').forEach(button=>button.onclick=()=>currentId&&favorite(currentId));
    $('#now-playing-folder').onclick=()=>currentId&&chooseFolder(currentId);
    $('#now-playing-system').onclick=()=>currentId&&action('open-file',currentId);
    document.querySelectorAll('[data-player]').forEach(button=>button.onclick=()=>{
      const kind=button.dataset.player;if(kind==='play')playCurrent();if(kind==='pause')audio().pause();if(kind==='next')move(1);if(kind==='previous')move(-1);if(kind==='shuffle'){shuffle=!shuffle;if(shuffle)shuffleQueue();renderQueue();}if(kind==='repeat'){repeat=repeat==='off'?'all':repeat==='all'?'one':'off';renderQueue();}refreshPlayer();
    });
    document.querySelectorAll('[data-player-seek]').forEach(input=>input.oninput=()=>{if(Number.isFinite(audio().duration))audio().currentTime=Math.min(Number(input.value),audio().duration);});
    document.querySelectorAll('[data-player-volume]').forEach(input=>input.oninput=()=>setVolume(Number(input.value)));
    document.querySelectorAll('[data-player-mute]').forEach(button=>button.onclick=()=>{if(audio().muted||audio().volume===0)setVolume(lastVolume);else{lastVolume=Math.sqrt(audio().volume)*100;audio().muted=true;savePlayer();refreshPlayer();}});
    audio().addEventListener('volumechange',()=>{if(!audio().muted&&audio().volume>0)lastVolume=Math.sqrt(audio().volume)*100;savePlayer();refreshPlayer();});
    for(const event of ['play','pause','timeupdate','durationchange','loadedmetadata'])audio().addEventListener(event,refreshPlayer);
    audio().addEventListener('error',()=>{playbackError();refreshPlayer();});
    audio().onended=()=>{const playable=playableQueue();if(repeat==='one'){audio().currentTime=0;playCurrent();}else if(repeat==='all'||playable.indexOf(currentId)<playable.length-1)move(1);else refreshPlayer();};
    $('#prepare-playback').onclick=async()=>{if(copyBusy||!currentId)return;copyBusy=true;$('#cancel-playback-copy').hidden=false;$('#player-error').textContent=text('media_preparing');$('#prepare-playback').disabled=true;try{await open(currentId,null,true,false);}finally{copyBusy=false;$('#cancel-playback-copy').hidden=true;$('#prepare-playback').disabled=false;refreshPlayer();}};
    $('#cancel-playback-copy').onclick=()=>action('cancel');
    $('#auto-engine-updates').onchange=()=>change('engine-updates-toggle',$('#auto-engine-updates').checked);
    $('#check-engine-updates').onclick=async()=>{updateBusy=true;renderUpdates();try{await change('engine-updates-check');}finally{updateBusy=false;renderUpdates();}};
    setInterval(async()=>{if(page!=='settings'||document.hidden)return;try{const updates=await window.torikomi.call('engine-updates-status');if(updates&&typeof updates.automatic==='boolean'){state.engineUpdates=updates;renderUpdates();}}catch{}},2000);
  }
  return {labels,text,init,render:renderMedia,navigation,isFavorite,favorite,chooseFolder,open};
})();
