const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('@playwright/test');

// The UI uses the same IPC surface as Tauri; native storage/processes have Rust tests.
async function mockBackend() {
  const callbacks = {};
  const preferences = {themeMode:'dark',themeColor:'violet',fontScale:1,fontFamily:'system',layout:'comfortable',language:'en',notifications:true,glass:false};
  const state = {deviceId:'test',document:{schemaVersion:2,settings:{updatedAt:0,deviceId:'test',value:preferences},entries:[],folders:[],memberships:[]}, files:{}, folder:'Downloads/Torikomi',engine:'yt-dlp',ffmpeg:'ffmpeg',isDark:true,glassAvailable:true};
  window.testCalls = [];
  window.forgetMediaFile = id => {delete state.files[id];emit();};
  state.engineUpdates={automatic:true,state:'idle',lastChecked:null,tools:{'yt-dlp':{version:'2026.08.19',managed:true},'gallery-dl':{version:'1.32.16',managed:false}}};
  window.failSetupOnce = true;
  state.setupComplete = false; state.gallery = ''; state.downloadAvailable = true;
  const emit = () => callbacks.state?.({payload:structuredClone(state)});
  window.seedLibraryPreviews = () => {
    for (const [id,kind] of [['local-photo','image'],['local-video','video'],['remote-photo','image']]) state.document.entries.push({id,url:'https://example.org/album',title:id,kind,createdAt:30,updatedAt:30,deviceId:'test',deletedAt:0});
    state.files['local-photo']='C:/Downloads/photo.png';state.files['local-video']='C:/Downloads/video.mp4';emit();
  };
  window.seedMediaLibrary = () => {
    state.setupComplete=true;
    for (const [id,kind,title] of [['song-one','audio','First audio fixture'],['song-two','audio','Second audio fixture'],['photo-one','image','Photo fixture'],['video-one','video','Video fixture']]) {
      state.document.entries.push({id,url:'https://example.org/'+id,title,kind,createdAt:40,updatedAt:40,deviceId:'test',deletedAt:0,metadata:kind==='audio'?{artist:'Fixture artist',album:'Fixture album',duration:3}:null});
      state.files[id]='C:/Downloads/'+id;
    }
    emit();
  };
  window.__TAURI__ = {
    event:{listen:async(name,callback) => { callbacks[name]=callback; return () => {}; }},
    core:{invoke:async(_command,{command,args}) => {
      window.testCalls.push({command,args});
      if (command === 'load') return structuredClone(state);
      if (command === 'media-open' && window.holdMediaOpen===args[0]) await new Promise(resolve=>{window.releaseMediaOpen=()=>{window.holdMediaOpen=null;resolve();};});
      if (command === 'engine-updates-status') return structuredClone(state.engineUpdates);
      if (command === 'engine-updates-toggle') {state.engineUpdates.automatic=args[0];emit();return structuredClone(state);}
      if (command === 'engine-updates-check') {state.engineUpdates.state='no_feed';state.engineUpdates.lastChecked=Date.now();emit();return structuredClone(state);}
      if (command === 'media-open' || command === 'media-compatible') { const entry=state.document.entries.find(e=>e.id===args[0]&&!e.deletedAt);if(!entry||!state.files[entry.id])throw Error('Missing local media');return {id:entry.id,title:entry.title,kind:entry.kind,url:entry.kind==='image'?window.previewFixture:window.mediaFixture,metadata:entry.metadata,duration:3}; }
      if (command === 'favorite') { state.document.memberships=state.document.memberships.filter(m=>m.entryId!==args[0]||(args[1]&&m.folderId!=='favorites'));if(args[1])state.document.memberships.push({folderId:'favorites',entryId:args[0],updatedAt:Date.now(),deviceId:'test',deletedAt:0});emit();return structuredClone(state); }
      if (command === 'folder-create') { state.document.folders.push({id:'folder-'+(state.document.folders.length+1),name:args[0],createdAt:Date.now(),updatedAt:Date.now(),deviceId:'test',deletedAt:0});emit();return structuredClone(state); }
      if (command === 'folder-rename') { state.document.folders.find(f=>f.id===args[0]).name=args[1];emit();return structuredClone(state); }
      if (command === 'folder-remove') { state.document.folders.find(f=>f.id===args[0]).deletedAt=Date.now();emit();return structuredClone(state); }
      if (command === 'folder-add' || command === 'folder-track-remove') { state.document.memberships=state.document.memberships.filter(m=>!(m.folderId===args[0]&&m.entryId===args[1]));if(command==='folder-add'){state.document.memberships.push({folderId:args[0],entryId:args[1],deletedAt:0});if(!state.document.memberships.some(m=>m.folderId==='favorites'&&m.entryId===args[1]&&!m.deletedAt))state.document.memberships.push({folderId:'favorites',entryId:args[1],deletedAt:0});}emit();return structuredClone(state); }
      if (command === 'setup-auto') {
        if (window.failSetupOnce) { window.failSetupOnce=false;throw Error('Download integrity check failed'); }
        if (window.holdSetup) {
          callbacks['setup-progress']?.({payload:{tool:'gallery-dl',phase:'downloading',received:50,total:100}});
          await new Promise((resolve,reject)=>{window.releaseSetup=resolve;window.cancelSetup=()=>reject(Error('Operation cancelled'));});
        }
        state.engine='yt-dlp';state.gallery='gallery-dl';state.ffmpeg='ffmpeg';state.setupComplete=true;emit();return structuredClone(state);
      }
      if (command === 'cancel') { window.cancelSetup?.();return null; }
      if (command === 'choose') { state[args[0]]='manual-'+args[0];emit();return structuredClone(state); }
      if (command === 'setup-complete') { state.setupComplete=true;emit();return structuredClone(state); }
      if (command === 'gallery-inspect') {
        if(window.holdGallery) await new Promise(resolve=>window.releaseGallery=resolve);
        if(args[0].includes('tiktok.com/@aurelionz_/photo/')) {
          if(args[0].includes('blocked=1'))throw Error("[tiktok][error] Failed to extract post (HttpError: '403 Forbidden')");
          return {url:args[0],items:[{id:'photo-1',imageIndex:1,title:'TikTok photo',ext:'jpeg',kind:'image'},{id:'music',imageIndex:2,title:'Background audio',ext:'mp4',kind:'audio'}]};
        }
        if(args[0].endsWith('/cancelled'))throw Error('Operation cancelled');
        if(!/\/(album|photos|partial)$/.test(args[0]))throw Error('No supported photos');
        const items=[{id:'gallery-1',imageIndex:1,title:'A long album caption repeated for every image. '.repeat(20),ext:'jpg',kind:'image'},{id:'gallery-2',imageIndex:2,title:'Video clip',ext:'mp4',kind:'video'},{id:'gallery-3',imageIndex:3,title:'Photo two',ext:'png',kind:'image'}];
        return {url:args[0],items:args[0].endsWith('/photos')?items.filter(item=>item.kind==='image'):items};
      }
      if (command === 'gallery-download') return structuredClone(state);
      if (command === 'library-preview') { if (args[0]==='local-video' && !window.videoPreviewRetried) { window.videoPreviewRetried=true;throw Error('Temporary video error'); } return window.previewFixture; }
      if (command === 'gallery-preview') {
        if (args[1] === 3 && !window.previewRetried) { window.previewRetried=true;throw Error('Temporary photo error'); }
        return window.previewFixture;
      }
      if (command === 'settings') { state.document.settings.value=args[0];emit();return structuredClone(state); }
      if (command === 'save-link') { const entry={id:'one',url:args[0],title:'Saved clip',kind:'link',createdAt:10,updatedAt:10,deviceId:'test',deletedAt:0};state.document.entries.push(entry);emit();return entry; }
      if (command === 'remove-link') { state.document.entries[0].deletedAt=20;emit();return null; }
      if (command === 'inspect' && window.holdInspection) await new Promise(resolve => { window.releaseInspection=resolve; });
      if (command === 'inspect' && args[0].includes('tiktok.com/@aurelionz_/photo/')) throw Error('ERROR: Unsupported URL: '+args[0]);
      if (command === 'inspect' && /\/(partial|unavailable)$/.test(args[0])) throw Error('Video formats unavailable');
      if (command === 'inspect') return {url:args[0],title:'Every format from the source',formats:[
        {id:'137',kind:'video',ext:'mp4',height:1080,fps:30,vcodec:'avc1',acodec:'none',sizeBytes:12000000},
        {id:'248',kind:'video',ext:'webm',height:1080,fps:60,vcodec:'vp9',acodec:'none',sizeBytes:9000000},
        {id:'22',kind:'muxed',ext:'mp4',height:720,fps:30,vcodec:'avc1',acodec:'aac',sizeBytes:7000000},
        {id:'140',kind:'audio',ext:'m4a',abr:128,vcodec:'none',acodec:'aac',sizeBytes:1000000},
        {id:'251',kind:'audio',ext:'webm',abr:160,vcodec:'none',acodec:'opus',sizeBytes:1500000},
      ]};
      if (command === 'download') { callbacks.progress?.({payload:{text:'progress:100%'}});return structuredClone(state); }
      if (command === 'backup-export') return {cancelled:false};
      if (command === 'backup-import-preview') return {cancelled:false,count:3,deletedCount:1};
      if (command === 'backup-import-apply') { state.document.settings.value.themeColor='mint';emit();return structuredClone(state); }
      if (command === 'backup-import-cancel') return null;
      return null;
    }}
  };
}

async function assertSidebarAligned(page) {
  const rows = await page.locator('.sidebar nav button').evaluateAll(buttons => buttons.map(button => {
    const edge = getComputedStyle(button).direction === 'rtl' ? 'right' : 'left';
    return {icon:button.querySelector('.icon').getBoundingClientRect()[edge], text:button.querySelector('span').getBoundingClientRect()[edge]};
  }));
  for (const row of rows) {
    assert.ok(Math.abs(row.icon - rows[0].icon) < 1,'Sidebar icons must share a column');
    assert.ok(Math.abs(row.text - rows[0].text) < 1,'Sidebar labels must share a column');
  }
}

async function main() {
  const ui = path.resolve(__dirname,'../ui');
  const server = http.createServer(async(req,res) => {
    const file = path.resolve(ui,'.' + new URL(req.url,'http://localhost').pathname);
    if (!file.startsWith(ui + path.sep) && file !== ui) { res.writeHead(403).end();return; }
    try { const target = file === ui ? path.join(ui,'index.html') : file; const body = await fs.readFile(target);res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png'})[path.extname(target)] || 'application/octet-stream');res.end(body); }
    catch { res.writeHead(404).end(); }
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const browser = await chromium.launch({headless:true});
  try {
    const previews=path.join(__dirname,'../build/previews');await fs.mkdir(previews,{recursive:true});
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(mockBackend);
    await page.addInitScript(() => { window.previewFixture='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ0YAAAAASUVORK5CYII='; });
    await page.goto(`http://127.0.0.1:${server.address().port}/index.html`);
    await page.waitForFunction(()=>typeof state!=='undefined' && state);
    await page.waitForSelector('#onboarding[open]');
    await page.click('#onboarding-next');
    assert.equal(await page.locator('#setup-finish').isDisabled(),true);
    await page.click('#setup-auto');
    await page.waitForSelector('#setup-error:not(:empty)');
    assert.match(await page.locator('#setup-error').textContent(),/integrity/);
    assert.equal(await page.locator('#setup-finish').isDisabled(),true);
    assert.equal(await page.evaluate(()=>state.setupComplete),false,'Failed engine setup must remain incomplete');
    await page.evaluate(()=>{window.holdSetup=true;});
    await page.click('#setup-auto');
    await page.waitForSelector('#setup-cancel:not([hidden])');
    assert.equal(await page.locator('#setup-meter').evaluate(element=>element.value),50);
    assert.match(await page.locator('#setup-progress').textContent(),/gallery-dl.*50%/);
    assert.equal(await page.locator('#setup-back').isDisabled(),true);
    await page.click('#setup-cancel');
    await page.waitForFunction(()=>!setupBusy);
    assert.equal(await page.evaluate(()=>state.setupComplete),false,'Cancellation must not finish setup');
    await page.evaluate(()=>{window.holdSetup=false;});
    await page.click('#setup-auto');
    await page.waitForFunction(()=>state.gallery==='gallery-dl');
    assert.equal(await page.evaluate(()=>state.setupComplete),true,'Verified automatic setup must complete without depending on the final button');
    await page.click('#setup-finish');
    await page.waitForSelector('#onboarding[open]',{state:'hidden'});
    await assertSidebarAligned(page);
    assert.equal(await page.locator('[data-download-mode]').count(),0,'A link must not need a media-mode selection');
    assert.equal(await page.locator('[data-i18n="desktop_engine_note"]').count(),0,'Engine implementation copy must be removed from the link form');
    await page.locator('#library-count').evaluate(element => { element.textContent='125'; });
    await assertSidebarAligned(page);
    await page.locator('#library-count').evaluate(element => { element.textContent='0'; });
    await page.emulateMedia({reducedMotion:'reduce'});
    await page.evaluate(() => { window.holdInspection=true; });
    await page.fill('#url','https://example.org/clip');
    await page.click('#inspect-button');
    await page.waitForSelector('#inspection-loading:not([hidden])');
    assert.equal(await page.locator('.loader').evaluate(element => element.getAnimations().some(animation => animation.playState==='running')),true,'Loading must stay active when Windows animations are reduced');
    const opacity = await page.locator('.loader').evaluate(element => getComputedStyle(element).opacity);
    await page.waitForFunction(before => getComputedStyle(document.querySelector('.loader')).opacity !== before,opacity);
    await page.emulateMedia({reducedMotion:'no-preference'});
    const transform = await page.locator('.loader').evaluate(element => getComputedStyle(element).transform);
    await page.waitForFunction(before => getComputedStyle(document.querySelector('.loader')).transform !== before,transform);
    await page.screenshot({path:path.join(previews,'loading-sidebar.png')});
    await page.evaluate(() => { window.holdInspection=false;window.releaseInspection(); });
    await page.waitForSelector('.format-option');
    assert.equal(await page.locator('.format-option').count(),5);
    assert.equal(await page.locator('.format-option').filter({hasText:'1080p'}).count(),2,'Same-height codecs must remain separate');
    assert.equal(await page.locator('#download-button').isDisabled(),true,'No automatic best choice');
    await page.check('input[value="248"]');await page.selectOption('#audio-track','251');await page.click('#download-button');
    await page.waitForFunction(()=>window.testCalls.some(c=>c.command==='download'));
    assert.deepEqual(await page.evaluate(()=>window.testCalls.find(c=>c.command==='download').args),['https://example.org/clip','248','251','original',null]);
    await page.click('[data-format-kind="audio"]');assert.equal(await page.locator('.format-option').count(),2);
    await page.check('input[value="140"]');await page.selectOption('#audio-output','mp3');await page.click('#download-button');
    await page.waitForFunction(()=>window.testCalls.filter(c=>c.command==='download').length===2);
    assert.deepEqual(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='download').at(-1).args),['https://example.org/clip','140',null,'mp3',null]);
    await page.fill('#url','https://example.org/album');await page.click('#inspect-button');
    await page.waitForSelector('#gallery-panel:not([hidden])');
    await page.waitForSelector('#format-panel:not([hidden])');
    await page.waitForFunction(()=>document.querySelector('#gallery-items img')?.naturalWidth>0);
    await page.waitForSelector('#gallery-preview-retry:not([hidden])');
    await page.click('#gallery-preview-retry');
    await page.waitForFunction(()=>[...document.querySelectorAll('#gallery-items img')].every(image=>image.naturalWidth>0));
    assert.equal(await page.locator('#gallery-items .format-main strong').first().evaluate(element=>element.getBoundingClientRect().height <= 2*parseFloat(getComputedStyle(element).lineHeight)+1),true,'A long caption must not replace the thumbnail');
    const previewCalls = await page.evaluate(()=>window.testCalls.filter(call=>call.command==='gallery-preview'));
    assert.deepEqual(previewCalls.map(call=>call.args),[['https://example.org/album',1],['https://example.org/album',3],['https://example.org/album',3]]);
    await page.screenshot({path:path.join(previews,'photo-previews.png')});
    await page.locator('#gallery-items .photo-preview').first().click();assert.equal(await page.locator('#gallery-selected').textContent(),'1');
    await page.locator('#gallery-items .photo-preview').first().click();assert.equal(await page.locator('#gallery-selected').textContent(),'2');
    assert.equal(await page.locator('#gallery-items input').count(),2,'Video entries cannot become selectable photos');
    assert.equal(await page.locator('#gallery-selected').textContent(),'2');
    await page.click('#gallery-none');assert.equal(await page.locator('#gallery-download').isDisabled(),true);
    await page.check('#gallery-items input[value="3"]');await page.click('#gallery-download');
    await page.waitForFunction(()=>window.testCalls.some(c=>c.command==='gallery-download'));
    assert.deepEqual(await page.evaluate(()=>window.testCalls.find(c=>c.command==='gallery-download').args),['https://example.org/album',[3]]);
    await page.click('#gallery-all');assert.equal(await page.locator('#gallery-selected').textContent(),'2');
    const videoChecks=await page.evaluate(()=>window.testCalls.filter(c=>c.command==='inspect').length);
    const tiktok='https://www.tiktok.com/@aurelionz_/photo/7470834434539916551?is_from_webapp=1&sender_device=pc';
    await page.fill('#url',tiktok);await page.click('#inspect-button');await page.waitForFunction(()=>!inspecting);
    assert.equal(await page.locator('#gallery-panel').isVisible(),true);assert.equal(await page.locator('#status').textContent(),'');
    assert.equal(await page.locator('#gallery-items input').count(),1,'Background music cannot be selected as a photo');
    await page.fill('#url',tiktok+'&blocked=1');await page.click('#inspect-button');await page.waitForFunction(()=>!inspecting);
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='inspect').length),videoChecks,'A TikTok photo failure must not try the unsupported video extractor');
    assert.match(await page.locator('#status').textContent(),/403 Forbidden/);
    assert.doesNotMatch(await page.locator('#status').textContent(),/Unsupported URL|No supported photos/);
    await page.fill('#url','https://example.org/photos');await page.click('#inspect-button');await page.waitForFunction(()=>!inspecting);
    assert.equal(await page.locator('#gallery-panel').isVisible(),true);assert.equal(await page.locator('#format-panel').isVisible(),false);
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='inspect').length),videoChecks,'A photo-only album must not require video inspection');
    await page.fill('#url','https://example.org/partial');await page.click('#inspect-button');await page.waitForFunction(()=>!inspecting);
    assert.equal(await page.locator('#gallery-panel').isVisible(),true,'A video error must keep photos usable');assert.match(await page.locator('#status').textContent(),/Video formats unavailable/);
    await page.fill('#url','https://example.org/unavailable');await page.click('#inspect-button');await page.waitForFunction(()=>!inspecting);
    assert.equal(await page.locator('#format-panel').isVisible(),false);assert.equal(await page.locator('#gallery-panel').isVisible(),false);assert.match(await page.locator('#status').textContent(),/Video formats unavailable/);
    const beforeCancel=await page.evaluate(()=>window.testCalls.filter(c=>c.command==='inspect').length);
    await page.fill('#url','https://example.org/cancelled');await page.click('#inspect-button');await page.waitForFunction(()=>!inspecting);
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='inspect').length),beforeCancel,'Cancellation must not start a fallback engine');
    await page.evaluate(()=>window.holdGallery=true);await page.fill('#url','https://example.org/album');await page.click('#inspect-button');await page.waitForFunction(()=>!!window.releaseGallery);
    await page.fill('#url','https://example.org/changed');await page.evaluate(()=>{window.holdGallery=false;window.releaseGallery();});await page.waitForFunction(()=>!inspecting);
    assert.equal(await page.locator('#gallery-panel').isVisible(),false,'A stale album must not replace the edited link');
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='inspect').length),beforeCancel,'A stale album must not start another engine');
    await page.fill('#url','https://example.org/clip');await page.click('#inspect-button');await page.waitForSelector('.format-option');
    await page.click('[data-format-kind="all"]');
    await page.click('#save-link');await page.waitForFunction(()=>state.document.entries.length===1);
    await page.click('[data-page="library"]');await page.locator('#entries .actions button').first().click();
    await page.click('#inspect-button');await page.waitForSelector('#formats input[value="140"]');await page.check('#formats input[value="140"]');await page.click('#download-button');
    await page.waitForFunction(()=>window.testCalls.filter(c=>c.command==='download').length===3);
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='download').at(-1).args[4]),null,'Downloading a saved link creates a media entry instead of trying to replace a link record');
    await page.click('[data-page="settings"]');
    const fields=await page.locator('.settings-grid select').evaluateAll(elements=>elements.map(select=>({height:select.getBoundingClientRect().height,appearance:getComputedStyle(select).appearance,arrow:!!select.parentElement.querySelector('.icon')})));
    assert.equal(fields.length,3);
    for(const field of fields){assert.ok(field.height>=48,'Dropdowns need a consistent comfortable target');assert.equal(field.appearance,'none');assert.equal(field.arrow,true,'Dropdown arrows need inset spacing');}
    const fontSelect=page.locator('#preferences [name="fontFamily"]');
    await fontSelect.focus();await page.keyboard.press('Tab');
    assert.equal(await fontSelect.evaluate(select=>document.activeElement!==select&&document.activeElement!==document.body),true,'Tab must advance keyboard focus from the font selector');
    await fontSelect.selectOption('sans');
    await page.waitForFunction(()=>state.document.settings.value.fontFamily==='sans');
    assert.match(await page.evaluate(()=>document.body.style.fontFamily),/Arial/,'The selected font must be applied');
    await page.selectOption('#preferences [name="fontFamily"]','system');
    await page.check('#preferences [name="glass"]');
    await page.waitForFunction(()=>document.body.classList.contains('glass'));
    assert.match(await page.evaluate(()=>getComputedStyle(document.body).backgroundImage),/0\.18|18%/,'Glass must expose the native backdrop through its theme tint');
    assert.equal(await page.evaluate(()=>getComputedStyle(document.querySelector('.sidebar')).backdropFilter),'none','Native glass needs no second full-sidebar blur');
    const transparency=await page.context().newCDPSession(page);
    await transparency.send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-transparency',value:'reduce'}]});
    assert.equal(await page.evaluate(()=>matchMedia('(prefers-reduced-transparency: reduce)').matches),true);
    assert.doesNotMatch(await page.evaluate(()=>getComputedStyle(document.body).backgroundImage),/0\.18|18%/,'Reduced transparency must restore an opaque surface');
    await transparency.send('Emulation.setEmulatedMedia',{features:[]});
    await page.evaluate(()=>{state.glassAvailable=false;render();});
    assert.equal(await page.locator('#preferences [name="glass"]').isDisabled(),true);
    assert.equal(await page.evaluate(()=>document.body.classList.contains('glass')),false,'Unavailable native glass keeps the solid layout');
    await page.evaluate(async()=>{state=await window.torikomi.call('load');render();});
    await page.uncheck('#preferences [name="glass"]');
    assert.equal(await page.evaluate(()=>document.body.classList.contains('glass')),false);
    await page.click('#manage-engines');await page.locator('#setup-manual summary').click();
    await page.locator('#onboarding [data-choose="gallery"]').click();await page.waitForFunction(()=>state.gallery==='manual-gallery');
    await page.keyboard.press('Escape');assert.equal(await page.locator('#onboarding').isVisible(),false);
    assert.equal(await page.locator('#language-options option').count(),25);
    assert.equal(await page.locator('#color-themes .swatch').count(),20);
    const before = await page.evaluate(()=>getComputedStyle(document.body).backgroundImage);
    await page.click('#color-themes [data-theme="sunset"]');
    await page.waitForFunction(()=>state.document.settings.value.themeColor==='sunset');
    assert.notEqual(await page.evaluate(()=>getComputedStyle(document.body).backgroundImage),before,'Theme must change real gradient');
    await page.click('#preview-theme');await page.click('#preview-swatches [data-theme="forest"]');
    assert.equal(await page.evaluate(()=>state.document.settings.value.themeColor),'sunset');
    await page.keyboard.press('Escape');assert.equal(await page.locator('#theme-preview').isVisible(),false);
    assert.equal(await page.locator('#preview-theme').evaluate(element=>element===document.activeElement),true);
    await page.click('#preview-theme');await page.click('#preview-swatches [data-theme="cobalt"]');await page.click('#apply-preview');
    await page.waitForFunction(()=>state.document.settings.value.themeColor==='cobalt');
    await page.locator('#color-themes [data-theme="cobalt"]').focus();await page.keyboard.press('Home');
    await page.waitForFunction(()=>state.document.settings.value.themeColor==='violet');
    await page.click('#backup-import');await page.waitForSelector('#backup-preview[open]');await page.click('#cancel-import');
    assert.equal(await page.evaluate(()=>state.document.settings.value.themeColor),'violet');
    await page.click('#backup-import');await page.click('#apply-import');await page.waitForFunction(()=>state.document.settings.value.themeColor==='mint');
    await page.click('#backup-export');await page.waitForFunction(()=>window.testCalls.some(c=>c.command==='backup-export'));
    for (const language of ['id','fr','ar','zh-TW']) {
      await page.selectOption('#language-options',language);await page.waitForFunction(code=>document.documentElement.lang===code,language);
      assert.equal(await page.locator('html').getAttribute('dir'),language==='ar'?'rtl':'ltr');
      await assertSidebarAligned(page);
    }
    await page.evaluate(()=>{const input=document.getElementById('preferences').elements.fontScale;input.value='1.5';input.dispatchEvent(new Event('change',{bubbles:true}));});
    await page.waitForFunction(()=>document.documentElement.style.fontSize==='24px');
    for (const language of ['de','ar']) {
      await page.selectOption('#language-options',language);await page.waitForFunction(code=>document.documentElement.lang===code,language);
      await assertSidebarAligned(page);
    }
    for (const width of [375,620,1024,1440]) {
      await page.setViewportSize({width,height:1000});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,`Overflow at ${width}`);
      await page.click('#manage-engines');
      assert.equal(await page.locator('#onboarding').evaluate(element=>element.scrollWidth<=element.clientWidth),true,`Setup overflow at ${width}`);
      await page.keyboard.press('Escape');
    }
    await page.evaluate(()=>{const input=document.getElementById('preferences').elements.fontScale;input.value='1';input.dispatchEvent(new Event('change',{bubbles:true}));});
    await page.waitForFunction(()=>document.documentElement.style.fontSize==='16px');
    await page.selectOption('#language-options','en');await page.click('#color-themes [data-theme="violet"]');
    await page.screenshot({path:path.join(previews,'settings-gradient.png'),fullPage:true});
    await page.click('[data-page="download"]');await page.screenshot({path:path.join(previews,'download-formats.png'),fullPage:true});
    await page.fill('#url','https://example.org/changed');assert.equal(await page.locator('#format-panel').isVisible(),false);
    await page.click('[data-page="library"]');await page.waitForSelector('.entry');page.once('dialog',dialog=>dialog.accept());await page.locator('.entry .actions button:last-child').click();
    await page.waitForFunction(()=>state.document.entries[0].deletedAt>0);
    await page.evaluate(()=>window.seedLibraryPreviews());
    await page.waitForFunction(()=>document.querySelector('[data-entry-id="local-photo"] img')?.naturalWidth>0);
    await page.waitForSelector('#library-preview-retry:not([hidden])');await page.click('#library-preview-retry');
    await page.waitForFunction(()=>document.querySelector('[data-entry-id="local-video"] img')?.naturalWidth>0);
    const libraryCalls = await page.evaluate(()=>window.testCalls.filter(call=>call.command==='library-preview').map(call=>call.args));
    assert.deepEqual(libraryCalls,[['local-photo'],['local-video'],['local-video']],'Only local media IDs may be requested for Library previews');
    await page.screenshot({path:path.join(previews,'library-previews.png')});
    await page.evaluate(()=>window.torikomi.call('settings',{...state.document.settings.value,themeMode:'light',fontScale:1.5,language:'ar'}));
    await page.setViewportSize({width:375,height:1000});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Library previews must fit mobile width and larger RTL text');
    await page.screenshot({path:path.join(previews,'library-previews-light-rtl.png'),fullPage:true});
    assert.deepEqual(errors,[]);
    console.log('UI smoke passed: aligned sidebar including counts/RTL/150% text, animated loading in both motion modes, setup, photo/video selection, gradients, JSON, languages and widths 375–1440.');
  } finally { await browser.close();await new Promise(resolve=>server.close(resolve)); }
}
if (require.main===module) main().catch(error=>{console.error(error);process.exitCode=1;});
module.exports={mockBackend};
