const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const http=require('node:http');
const path=require('node:path');
const {chromium}=require('@playwright/test');
const {mockBackend}=require('./ui-smoke.cjs');

function wave() {
  const rate=8000, samples=rate*30, buffer=Buffer.alloc(44+samples*2);
  buffer.write('RIFF');buffer.writeUInt32LE(buffer.length-8,4);buffer.write('WAVEfmt ',8);buffer.writeUInt32LE(16,16);buffer.writeUInt16LE(1,20);buffer.writeUInt16LE(1,22);buffer.writeUInt32LE(rate,24);buffer.writeUInt32LE(rate*2,28);buffer.writeUInt16LE(2,32);buffer.writeUInt16LE(16,34);buffer.write('data',36);buffer.writeUInt32LE(samples*2,40);
  for(let i=0;i<samples;i++) buffer.writeInt16LE(Math.round(200*Math.sin(i*440*Math.PI*2/rate)),44+i*2);
  return buffer;
}
async function assertIconActions(page,selector) {
  const buttons=await page.locator(selector).evaluateAll(elements=>elements.filter(button=>button.getClientRects().length).map(button=>({text:button.textContent.trim(),label:button.getAttribute('aria-label'),title:button.title,svg:!!button.querySelector('svg'),width:button.getBoundingClientRect().width,height:button.getBoundingClientRect().height})));
  assert.ok(buttons.length>0);
  for(const button of buttons){
    assert.equal(button.text,'','Media actions must use icons');assert.ok(button.svg);assert.ok(button.label);assert.equal(button.title,button.label,'Icons need both an accessible name and a tooltip');
    assert.ok(button.width>=44&&button.height>=44,`${button.label} must keep a 44px click target (received ${button.width}×${button.height})`);
  }
}
async function playerPreferences(page) {
  return page.evaluate(()=>JSON.parse(localStorage.getItem(`torikomi.player.${state.deviceId||state.document.settings.deviceId||'local'}`)));
}
async function assertNoPlaybackHistory(page) {
  assert.equal(await page.locator('#player-history,#player-history-empty,#clear-playback-history').count(),0,'The player has no playback history controls');
  assert.equal(Object.hasOwn(await playerPreferences(page)||{},'history'),false,'Player preferences do not store playback history');
}
async function setVolume(page,percentage,selector='#now-playing') {
  await page.locator(`${selector} [data-player-volume]`).evaluate((input,value)=>{input.value=String(value);input.dispatchEvent(new Event('input'));},percentage);
}
async function assertVolume(page,percentage) {
  await page.waitForFunction(expected=>[...document.querySelectorAll('[data-player-volume]')].every(input=>Number(input.value)===expected),percentage);
  const controls=await page.locator('[data-player-volume]').evaluateAll(inputs=>inputs.map(input=>({min:input.min,max:input.max,step:input.step})));
  assert.deepEqual(controls,[{min:'0',max:'100',step:'1'},{min:'0',max:'100',step:'1'}]);
  assert.deepEqual(await page.locator('[data-volume-value]').allTextContents(),[`${percentage}%`,`${percentage}%`]);
  assert.ok(Math.abs(await page.locator('#audio-player').evaluate(a=>a.volume)-(percentage/100)**2)<0.000001,`${percentage}% must use a quiet-end volume curve`);
}
async function assertMiniPlayerLayout(page) {
  await page.evaluate(()=>window.scrollTo(0,0));
  await page.waitForFunction(()=>Math.abs(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--player-height'))-document.querySelector('#player-bar').getBoundingClientRect().height)<1);
  const layout=await page.evaluate(()=>{
    const sidebar=document.querySelector('.sidebar'),bar=document.querySelector('#player-bar');
    const side=sidebar.getBoundingClientRect(),dock=bar.getBoundingClientRect();
    return {sideBottom:side.bottom,sideHeight:side.height,sideOverflow:sidebar.scrollWidth>sidebar.clientWidth,dockTop:dock.top,dockBottom:dock.bottom,dockHeight:dock.height,height:innerHeight,width:innerWidth,mainPadding:parseFloat(getComputedStyle(document.querySelector('main')).paddingBottom),overflow:document.documentElement.scrollWidth>innerWidth};
  });
  assert.ok(Math.abs(layout.dockBottom-layout.height)<1,'Mini player stays flush with the window bottom');
  assert.equal(layout.overflow,false,'Mini player must not create horizontal overflow');
  assert.equal(layout.sideOverflow,false,'Sidebar must not need horizontal scrolling');
  assert.ok(layout.mainPadding>=layout.dockHeight+20,'Page content reserves the actual player height');
  if(layout.width>760) assert.ok(layout.sideBottom>=layout.dockTop,'Sidebar background reaches the player without a gap');
  else assert.ok(layout.sideHeight<350,'Narrow navigation does not inherit desktop player height');
  const scrollbar=await page.evaluate(()=>{
    const root=document.documentElement,probe=document.createElement('span');root.append(probe);
    const expected=['--muted','--accent','--ink'].map(token=>{probe.style.color=`var(${token})`;return getComputedStyle(probe).color;});probe.remove();
    return {width:getComputedStyle(root,'::-webkit-scrollbar').width,track:getComputedStyle(root,'::-webkit-scrollbar-track').backgroundColor,thumb:getComputedStyle(root,'::-webkit-scrollbar-thumb').backgroundColor,expected};
  });
  assert.equal(scrollbar.width,'12px','Scrollbar keeps a compact usable track');
  assert.equal(scrollbar.track,'rgba(0, 0, 0, 0)','Scrollbar track blends with its surface');
  assert.ok(scrollbar.expected.includes(scrollbar.thumb),'Scrollbar thumb follows the active theme in normal/hover/active states');
}
async function assertTrackRows(page) {
  const rows=await page.locator('#player-queue .queue-track').evaluateAll(elements=>elements.map(row=>{
    const rect=row.getBoundingClientRect(),parts=['.photo-preview','.queue-track-details strong','.queue-track-details small',':scope > .icon'].map(selector=>{
      const element=row.querySelector(selector),bounds=element?.getBoundingClientRect();
      return bounds?{center:bounds.top+bounds.height/2,width:bounds.width,height:bounds.height,lineHeight:parseFloat(getComputedStyle(element).lineHeight)}:null;
    });
    return {label:row.getAttribute('aria-label'),parts,width:row.clientWidth,scrollWidth:row.scrollWidth,left:rect.left,right:rect.right,viewport:innerWidth};
  }));
  assert.ok(rows.length>0);
  for(const row of rows){
    assert.ok(row.parts.every(part=>part&&part.width>0&&part.height>0),`${row.label} keeps its artwork, title, artist and action visible`);
    assert.ok(row.parts.every(part=>Math.abs(part.center-row.parts[0].center)<2),`${row.label} stays on one horizontal line`);
    assert.ok(row.parts.slice(1,3).every(part=>!Number.isFinite(part.lineHeight)||part.height<=part.lineHeight+1),`${row.label} text does not wrap`);
    assert.ok(row.scrollWidth<=row.width+1&&row.left>=0&&row.right<=row.viewport+1,`${row.label} fits the viewport`);
  }
}
async function assertCurrentTrack(page,id,playing=true) {
  await page.waitForFunction(({id,playing})=>{
    const current=document.querySelector('#player-queue [aria-current="true"]');
    return current?.dataset.queueId===id&&current.querySelector(':scope > .icon use')?.getAttribute('href')===(playing?'#i-pause':'#i-play');
  },{id,playing});
  assert.equal(await page.locator('#player-queue [aria-current="true"]').count(),1);
  const colors=await page.locator('#player-queue .queue-track').evaluateAll(rows=>rows.map(row=>({current:row.getAttribute('aria-current')==='true',background:getComputedStyle(row).backgroundColor,border:getComputedStyle(row).borderColor})));
  const current=colors.find(row=>row.current);
  for(const row of colors.filter(row=>!row.current)){
    assert.notEqual(current.background,row.background,'The current track has a different background');
    assert.notEqual(current.border,row.border,'The current track has an accent border');
  }
}
async function main() {
  const bytes=wave(),root=path.resolve(__dirname,'../ui');
  const server=http.createServer(async(req,res)=>{
    if(req.url==='/fixture.wav'){res.writeHead(200,{'Content-Type':'audio/wav','Content-Length':bytes.length,'Accept-Ranges':'bytes'});res.end(bytes);return;}
    const file=path.join(root,req.url==='/'?'index.html':req.url.split('?')[0]);
    if(!file.startsWith(root+path.sep)){res.writeHead(403);res.end();return;}
    try {const data=await fs.readFile(file);res.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'text/html'});res.end(data);}catch{res.writeHead(404);res.end();}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin=`http://127.0.0.1:${server.address().port}`;
  const browser=await chromium.launch();
  try {
    const page=await browser.newPage({viewport:{width:1280,height:900}}), errors=[];
    page.setDefaultTimeout(3000);page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(mockBackend);
    await page.addInitScript(url=>{window.mediaFixture=url;window.previewFixture='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';},origin+'/fixture.wav');
    await page.goto(origin);await page.waitForFunction(()=>typeof state!=='undefined'&&state);
    await page.evaluate(()=>{window.seedMediaLibrary();document.querySelector('#onboarding').close();});
    await page.click('[data-page="library"]');
    await page.click('[data-history-kind="audio"]');assert.equal(await page.locator('#entries .entry').count(),2);
    await assertIconActions(page,'#entries .entry .actions button');
    await page.fill('#search','Fixture artist');assert.equal(await page.locator('#entries .entry').count(),2);
    await page.fill('#search','');
    await page.locator('#entries > [data-entry-id="song-one"] .actions button').first().focus();await page.keyboard.press('Enter');
    await page.click('#inspect-button');await page.waitForSelector('#formats input[value="140"]');await page.check('#formats input[value="140"]');await page.click('#download-button');
    await page.waitForFunction(()=>window.testCalls.some(c=>c.command==='download'));
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='download').at(-1).args[4]),'song-one','Same-kind redownload must retain its History and favorite identity');
    await page.check('#formats input[value="248"]');await page.click('#download-button');
    await page.waitForFunction(()=>window.testCalls.filter(c=>c.command==='download').length===2);
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='download').at(-1).args[4]),null,'A different media type must create a separate History entry');
    await page.click('[data-page="library"]');
    await page.locator('#entries [data-favorite="song-one"]').click();
    await page.click('[data-page="favorites"]');
    await page.click('#new-favorite-folder');await page.fill('#folder-name','MusicKu');await page.click('#save-folder');
    await page.waitForSelector('#favorite-folders [data-folder]');
    await page.click('[data-page="library"]');await page.locator('#entries [data-add-folder="song-one"]').click();
    const collectionSize=await page.locator('#collection-options input').evaluate(input=>{const checkbox=input.getBoundingClientRect(),label=input.closest('label').getBoundingClientRect();return {width:checkbox.width,height:checkbox.height,labelHeight:label.height};});
    assert.equal(collectionSize.width,18,'Folder membership uses a compact checkbox');assert.equal(collectionSize.height,18);
    assert.ok(collectionSize.labelHeight>=48,'The full folder label remains an easy click target');
    await page.locator('#collection-options input').focus();await page.keyboard.press('Space');
    await page.waitForFunction(()=>document.querySelector('#collection-options input').checked&&!document.querySelector('#collection-options input').disabled&&state.document.memberships.some(item=>item.folderId==='folder-1'&&item.entryId==='song-one'&&!item.deletedAt));
    await page.locator('#collection-options label span').click();
    await page.waitForFunction(()=>!document.querySelector('#collection-options input').checked&&!document.querySelector('#collection-options input').disabled&&!state.document.memberships.some(item=>item.folderId==='folder-1'&&item.entryId==='song-one'&&!item.deletedAt));
    await page.locator('#collection-options label span').click();
    await page.waitForFunction(()=>document.querySelector('#collection-options input').checked&&!document.querySelector('#collection-options input').disabled);
    await fs.mkdir(path.resolve(__dirname,'../build/previews'),{recursive:true});
    await page.screenshot({path:path.resolve(__dirname,'../build/previews/folder-membership.png'),fullPage:true});
    await page.click('#close-collections');
    await page.click('[data-page="favorites"]');await page.click('#favorite-folders [data-folder="folder-1"]');
    assert.equal(await page.locator('#favorite-tracks .entry').count(),1);
    assert.equal(await page.locator('#favorite-title').textContent(),'MusicKu');
    await assertIconActions(page,'#favorite-tracks .entry .actions button');
    await page.locator('#favorite-tracks [data-play="song-one"]').click();
    assert.equal(await page.locator('main > #now-playing.page').isVisible(),true,'Play must open a dedicated player page');
    assert.equal(await page.locator('#now-playing').evaluate(element=>element.tagName),'SECTION');
    assert.equal(await page.locator('#player-bar').isVisible(),false,'The full player does not need a duplicate bottom bar');
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    await assertVolume(page,100);
    assert.equal(await page.locator('#player-queue-panel').isVisible(),true,'The play queue stays visible with only the current track');
    assert.equal(await page.locator('#player-queue-empty').isVisible(),true);
    await assertNoPlaybackHistory(page);
    await assertCurrentTrack(page,'song-one');await assertTrackRows(page);
    await page.locator('#player-queue [data-queue-id="song-one"] .photo-preview').scrollIntoViewIfNeeded();
    await page.waitForFunction(()=>document.querySelector('#player-queue [data-queue-id="song-one"] .photo-preview img').naturalWidth>0);
    const previewCalls=await page.evaluate(()=>window.testCalls.filter(call=>call.command==='library-preview').length);
    const mediaOpens=await page.evaluate(()=>window.testCalls.filter(call=>call.command==='media-open').length);
    await page.click('#player-queue [data-queue-id="song-one"]');
    await page.waitForFunction(()=>document.querySelector('#audio-player').paused);
    await assertCurrentTrack(page,'song-one',false);
    assert.equal(await page.evaluate(()=>document.activeElement?.dataset.queueId),'song-one','Refreshing a paused row preserves keyboard focus');
    await page.keyboard.press('Enter');
    await page.waitForFunction(()=>!document.querySelector('#audio-player').paused);
    await assertCurrentTrack(page,'song-one');
    assert.equal(await page.evaluate(()=>document.activeElement?.dataset.queueId),'song-one','The current row stays focused after keyboard resume');
    await page.waitForFunction(()=>document.querySelector('#player-queue [data-queue-id="song-one"] .photo-preview img').naturalWidth>0);
    assert.equal(await page.evaluate(()=>window.testCalls.filter(call=>call.command==='media-open').length),mediaOpens,'The current row pauses and resumes without reopening its file');
    assert.equal(await page.evaluate(()=>window.testCalls.filter(call=>call.command==='library-preview').length),previewCalls,'Queue refreshes reuse the thumbnail cache');
    await assertNoPlaybackHistory(page);
    await setVolume(page,5);await assertVolume(page,5);
    await setVolume(page,0);await assertVolume(page,0);
    await page.locator('#now-playing [data-player-mute]').click();await assertVolume(page,5);
    await setVolume(page,100);await assertVolume(page,100);
    await setVolume(page,35);await assertVolume(page,35);
    await page.locator('#now-playing [data-player-mute]').click();
    assert.equal(await page.locator('#audio-player').evaluate(a=>a.muted),true);
    assert.deepEqual(await page.locator('[data-player-mute]').evaluateAll(buttons=>buttons.map(button=>button.getAttribute('aria-label'))),['Unmute','Unmute']);
    await page.click('[data-page="download"]');assert.equal(await page.locator('#player-bar').isVisible(),true);
    await assertMiniPlayerLayout(page);
    for(const [width,scale,mode] of [[1000,2,'dark'],[780,1.5,'light'],[700,1,'light'],[620,1.5,'dark'],[375,1.5,'light']]) {
      await page.setViewportSize({width,height:900});
      await page.evaluate(({scale,mode})=>window.torikomi.call('settings',{...state.document.settings.value,fontScale:scale,themeMode:mode}),{scale,mode});
      await assertMiniPlayerLayout(page);
    }
    await page.setViewportSize({width:1280,height:900});
    await page.evaluate(()=>window.torikomi.call('settings',{...state.document.settings.value,fontScale:1,themeMode:'dark'}));
    await assertMiniPlayerLayout(page);
    await page.mouse.move(1000,500);await page.mouse.wheel(0,300);
    await page.waitForFunction(()=>scrollY>0);
    await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));
    assert.ok(await page.evaluate(()=>document.querySelector('#download-button').getBoundingClientRect().bottom<=document.querySelector('#player-bar').getBoundingClientRect().top),'Last page action remains above the player after scrolling');
    await page.evaluate(()=>window.scrollTo(0,0));
    await page.screenshot({path:path.resolve(__dirname,'../build/previews/player-bar-scrollbar.png')});
    await page.locator('#player-bar [data-player-mute]').click();await assertVolume(page,35);
    assert.equal(await page.locator('#audio-player').evaluate(a=>a.muted),false);
    await assertIconActions(page,'#player-bar [data-player-mute]');
    assert.equal(await page.locator('#audio-player').evaluate(a=>a.paused),false,'Playback must continue while browsing another page');
    await page.locator('#player-bar [data-player="pause"]').click();assert.equal(await page.locator('#audio-player').evaluate(a=>a.paused),true);
    await page.locator('#player-bar [data-player="play"]').click();
    await page.waitForFunction(()=>!document.querySelector('#audio-player').paused);
    await assertNoPlaybackHistory(page);
    await setVolume(page,5,'#player-bar');await assertVolume(page,5);
    await page.locator('#audio-player').evaluate(a=>{a.volume=0.25;});await assertVolume(page,50);
    await page.locator('#audio-player').evaluate(a=>{a.muted=true;});
    await page.waitForFunction(()=>[...document.querySelectorAll('[data-volume-value]')].every(output=>output.textContent==='0%'));
    assert.deepEqual(await page.locator('[data-player-mute]').evaluateAll(buttons=>buttons.map(button=>button.getAttribute('aria-label'))),['Unmute','Unmute']);
    await page.locator('#audio-player').evaluate(a=>{a.muted=false;});await assertVolume(page,50);
    await setVolume(page,35,'#player-bar');
    await page.click('#show-now-playing');assert.equal(await page.locator('#now-playing').isVisible(),true);
    await page.waitForFunction(()=>parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--player-height'))===0);
    await page.click('#now-playing-back');assert.equal(await page.locator('#download').isVisible(),true,'Back must return to the page that opened the player');
    await page.click('#show-now-playing');await page.keyboard.press('Escape');assert.equal(await page.locator('#now-playing').isVisible(),false);
    await page.click('[data-page="library"]');await page.locator('#entries [data-play="song-one"]').click();
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    await assertNoPlaybackHistory(page);
    await assertVolume(page,35);
    assert.equal(await page.locator('#player-queue [data-queue-id="song-one"]').getAttribute('aria-current'),'true','The play queue includes and marks the current track');
    assert.equal(await page.locator('#player-queue [data-queue-id]').first().getAttribute('data-queue-id'),'song-one','The current track comes before upcoming tracks');
    await assertCurrentTrack(page,'song-one');await assertTrackRows(page);
    assert.equal(await page.locator('#player-queue [data-queue-id="song-two"] .photo-preview').count(),1,'Upcoming tracks include artwork');
    await page.click('#player-queue [data-queue-id="song-two"]');await page.waitForFunction(()=>document.querySelector('#now-playing-title').textContent==='Second audio fixture');
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    await assertCurrentTrack(page,'song-two');await assertTrackRows(page);
    await assertNoPlaybackHistory(page);
    await page.click('[data-page="library"]');await page.locator('#entries [data-play="song-one"]').click();
    await page.waitForFunction(()=>document.querySelector('#now-playing-title').textContent==='First audio fixture'&&document.querySelector('#audio-player').currentTime>0);
    await assertCurrentTrack(page,'song-one');
    await page.locator('#now-playing [data-player="previous"]').click();await page.waitForFunction(()=>document.querySelector('#now-playing-title').textContent==='Second audio fixture'&&document.querySelector('#audio-player').currentTime>0);
    await assertCurrentTrack(page,'song-two');
    await page.locator('#now-playing [data-player="previous"]').click();await page.waitForFunction(()=>document.querySelector('#now-playing-title').textContent==='First audio fixture');
    await page.locator('#now-playing [data-player-seek]').evaluate(input=>{input.value='1';input.dispatchEvent(new Event('input'));});
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>=1);
    await setVolume(page,35);await assertVolume(page,35);
    await page.locator('#now-playing [data-player="next"]').click();await page.waitForFunction(()=>document.querySelector('#player-title').textContent==='Second audio fixture');
    await page.locator('#now-playing [data-player="previous"]').click();await page.waitForFunction(()=>document.querySelector('#player-title').textContent==='First audio fixture');
    await page.locator('#now-playing [data-player="shuffle"]').click();assert.equal(await page.locator('#now-playing [data-player="shuffle"]').getAttribute('aria-pressed'),'true');
    const shuffledNext=await page.locator('#player-queue [data-queue-id]:not([aria-current])').first().getAttribute('data-queue-id');
    await page.locator('#now-playing [data-player="next"]').click();
    await page.waitForFunction(id=>document.querySelector('#audio-player').currentTime>0&&document.querySelector('#player-title').textContent===(id==='song-one'?'First audio fixture':'Second audio fixture'),shuffledNext);
    await page.locator('#now-playing [data-player="previous"]').click();await page.waitForFunction(()=>document.querySelector('#player-title').textContent==='First audio fixture'&&document.querySelector('#audio-player').currentTime>0);
    await page.locator('#now-playing [data-player="shuffle"]').click();
    await page.locator('#now-playing [data-player="repeat"]').click();await page.locator('#now-playing [data-player="repeat"]').click();
    assert.equal(await page.locator('#now-playing [data-player="repeat"]').getAttribute('aria-label'),'Repeat one');
    assert.equal(await page.locator('#now-playing [data-player="repeat"] svg').count(),1,'Repeat mode must retain its icon');
    await page.locator('#now-playing [data-player="repeat"]').click();
    await page.click('[data-page="download"]');
    await page.locator('#audio-player').evaluate(a=>a.dispatchEvent(new Event('ended')));
    await page.waitForFunction(()=>document.querySelector('#player-title').textContent==='Second audio fixture');
    assert.equal(await page.locator('#download').isVisible(),true,'Advancing the queue must preserve the current browsing page');
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    await page.locator('#audio-player').evaluate(a=>a.dispatchEvent(new Event('error')));
    assert.equal(await page.locator('#now-playing').isVisible(),true,'Playback failure must show the recovery controls');
    assert.equal(await page.locator('#prepare-playback').isVisible(),true);
    await page.click('#prepare-playback');
    await page.waitForFunction(()=>window.testCalls.some(c=>c.command==='media-compatible'));
    await page.keyboard.press('Escape');
    await page.click('[data-page="favorites"]');
    await page.click('#favorite-rename');await page.fill('#folder-name','Music renamed');await page.click('#save-folder');
    assert.equal(await page.locator('#favorite-title').textContent(),'Music renamed');
    await page.getByRole('button',{name:'Remove from this folder',exact:true}).click();
    assert.equal(await page.locator('#favorite-tracks .entry').count(),0);
    await page.click('#favorite-back');await page.click('#favorite-folders [data-folder="favorites"]');
    assert.equal(await page.locator('#favorite-tracks .entry').count(),1,'Removing from a folder preserves the favorite');
    await page.locator('#favorite-tracks [data-favorite="song-one"]').click();
    assert.equal(await page.locator('#favorite-tracks .entry').count(),0);
    await page.click('#favorite-back');await page.click('#favorite-folders [data-folder="folder-1"]');
    page.once('dialog',dialog=>dialog.accept());await page.click('#favorite-delete');
    assert.equal(await page.locator('#favorite-folders [data-folder="folder-1"]').count(),0);
    await page.click('[data-page="settings"]');await page.uncheck('#auto-engine-updates');
    assert.equal(await page.locator('#auto-engine-updates').isChecked(),false);
    await page.click('#check-engine-updates');await page.waitForFunction(()=>document.querySelector('#engine-update-status').textContent.includes('No engine update package'));
    assert.match(await page.locator('#engine-versions').textContent(),/User managed/);
    await assertNoPlaybackHistory(page);
    await page.click('[data-page="library"]');await page.click('[data-history-kind="image"]');assert.equal(await page.locator('#entries .entry').count(),1);
    await page.locator('#entries [data-play="photo-one"]').click();await page.waitForSelector('#media-viewer[open]');
    await page.waitForFunction(()=>document.querySelector('#viewer-image').naturalWidth>0);
    await page.keyboard.press('Escape');assert.equal(await page.locator('#media-viewer[open]').count(),0);
    await page.click('[data-history-kind="audio"]');
    await page.evaluate(()=>window.holdMediaOpen='song-one');await page.locator('#entries [data-play="song-one"]').click();
    await page.waitForFunction(()=>typeof window.releaseMediaOpen==='function');
    assert.equal(await page.locator('#now-playing').isVisible(),true,'The player page must open before media preparation returns');
    await page.click('[data-page="library"]');await page.click('[data-history-kind="video"]');await page.locator('#entries [data-play="video-one"]').click();
    await page.waitForSelector('#media-viewer[open]');await page.evaluate(()=>window.releaseMediaOpen());
    await page.waitForFunction(()=>window.testCalls.some(c=>c.command==='media-release'));
    assert.equal(await page.locator('#audio-player').evaluate(a=>a.paused),true,'A stale audio request cannot restart under video');
    await assertNoPlaybackHistory(page);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#now-playing').getAttribute('aria-busy'),'false','Video interruption must end pending audio preparation');
    await page.locator('#player-bar [data-player="play"]').click();await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    assert.equal(await page.locator('#library').isVisible(),true,'Resuming interrupted audio must preserve the browsing page');
    await page.click('[data-history-kind="audio"]');
    await page.evaluate(()=>window.forgetMediaFile('song-two'));await page.locator('#entries [data-play="song-one"]').click();
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    const opens=await page.evaluate(()=>window.testCalls.filter(c=>c.command==='media-open').length);
    await page.locator('#audio-player').evaluate(a=>a.dispatchEvent(new Event('ended')));
    assert.equal(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='media-open').length),opens,'Repeat off must stop before unavailable trailing tracks');
    await page.locator('#now-playing [data-player="previous"]').click();
    assert.notEqual(await page.evaluate(()=>window.testCalls.filter(c=>c.command==='media-open').at(-1)?.args[0]),'song-two','Previous must skip media that is no longer available');
    await page.click('[data-page="library"]');await page.click('[data-history-kind="all"]');
    await page.evaluate(()=>window.torikomi.call('settings',{...state.document.settings.value,themeMode:'light',language:'ar',fontScale:1.5}));
    await page.setViewportSize({width:375,height:900});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Media controls must fit RTL and 150% text');
    await page.screenshot({path:path.resolve(__dirname,'../build/previews/media-history.png'),fullPage:true});
    await page.locator('#entries [data-play="song-one"]').click();
    assert.equal(await page.evaluate(()=>scrollY),0,'Opening the player must reset the prior History scroll position');
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'The player page must fit RTL and 150% text');
    await assertTrackRows(page);await assertCurrentTrack(page,'song-one');
    assert.equal(await page.locator('#now-playing-favorite').getAttribute('aria-label')!==null,true);
    assert.equal(await page.locator('.skip').evaluate(element=>element.getBoundingClientRect().bottom<=0),true,'The skip link must stay outside the viewport until focused');
    await page.screenshot({path:path.resolve(__dirname,'../build/previews/player-page-light-rtl.png'),fullPage:true});
    await page.evaluate(()=>window.torikomi.call('settings',{...state.document.settings.value,themeMode:'dark',fontScale:1,language:'en'}));
    await page.setViewportSize({width:1280,height:900});
    await assertIconActions(page,'#now-playing [data-player]');
    await page.click('#now-playing-favorite');assert.equal(await page.locator('#now-playing-favorite').getAttribute('aria-pressed'),'true');
    await page.click('#now-playing-favorite');assert.equal(await page.locator('#now-playing-favorite').getAttribute('aria-pressed'),'false');
    await page.click('#now-playing-folder');await page.waitForSelector('#collection-dialog[open]');await page.click('#close-collections');
    await page.click('#now-playing-system');
    assert.deepEqual(await page.evaluate(()=>window.testCalls.filter(call=>call.command==='open-file').at(-1)?.args),['song-one'],'The full player must open the current media in the system app');
    await page.screenshot({path:path.resolve(__dirname,'../build/previews/player-page.png'),fullPage:true});
    await assertIconActions(page,'#now-playing [data-player-mute]');
    await setVolume(page,5);await page.locator('#now-playing [data-player-mute]').click();
    const beforeReload=await playerPreferences(page);
    await page.reload();await page.waitForFunction(()=>typeof state!=='undefined'&&state);
    await page.evaluate(()=>{window.seedMediaLibrary();document.querySelector('#onboarding').close();});
    assert.deepEqual(await playerPreferences(page),beforeReload,'Volume preferences survive restart while the media library reloads');
    await assertNoPlaybackHistory(page);
    assert.equal(await page.locator('#audio-player').evaluate(a=>a.muted),true,'Mute survives restart');
    await page.click('[data-page="library"]');await page.click('[data-history-kind="audio"]');await page.locator('#entries [data-play="song-two"]').click();
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    await assertNoPlaybackHistory(page);
    await page.locator('#now-playing [data-player-mute]').click();await assertVolume(page,5);
    await page.evaluate(()=>localStorage.setItem(`torikomi.player.${state.deviceId||state.document.settings.deviceId||'local'}`,JSON.stringify({volume:35,muted:true,lastVolume:35,history:[{entryId:'song-one',playedAt:Date.now()}]})));
    await page.reload();await page.waitForFunction(()=>typeof state!=='undefined'&&state);
    assert.deepEqual(await playerPreferences(page),{volume:35,muted:true,lastVolume:35},'Startup removes legacy playback history while preserving volume and mute');
    await page.evaluate(()=>{window.seedMediaLibrary();document.querySelector('#onboarding').close();});
    await assertNoPlaybackHistory(page);
    assert.equal(await page.locator('#audio-player').evaluate(a=>a.muted),true);
    await page.click('[data-page="library"]');await page.click('[data-history-kind="audio"]');await page.locator('#entries [data-play="song-two"]').click();
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    await page.locator('#now-playing [data-player-mute]').click();await assertVolume(page,35);
    await page.evaluate(()=>localStorage.setItem(`torikomi.player.${state.deviceId||state.document.settings.deviceId||'local'}`,'{broken'));
    await page.reload();await page.waitForFunction(()=>typeof state!=='undefined'&&state);
    await page.evaluate(()=>{window.seedMediaLibrary();document.querySelector('#onboarding').close();});
    await assertVolume(page,100);
    await setVolume(page,100);
    await page.click('[data-page="library"]');await page.click('[data-history-kind="audio"]');
    await page.locator('#audio-player').evaluate(a=>{window.originalAudioPlay=a.play;a.play=async()=>{throw new DOMException('Playback blocked by test','NotAllowedError');};});
    await page.locator('#entries [data-play="song-one"]').click();
    await page.waitForFunction(()=>document.querySelector('#player-error').textContent.length>0);
    await assertNoPlaybackHistory(page);
    await page.locator('#audio-player').evaluate(a=>{a.play=window.originalAudioPlay;});
    await page.evaluate(()=>localStorage.setItem(`torikomi.player.${state.deviceId||state.document.settings.deviceId||'local'}`,JSON.stringify({volume:150,muted:'yes',lastVolume:-5})));
    await page.reload();await page.waitForFunction(()=>typeof state!=='undefined'&&state);
    await page.evaluate(()=>{window.seedMediaLibrary();document.querySelector('#onboarding').close();});
    await assertVolume(page,100);
    assert.equal(await page.locator('#audio-player').evaluate(a=>a.muted),false,'An invalid saved mute value does not mute audio');
    await page.click('[data-page="library"]');await page.click('[data-history-kind="audio"]');await page.locator('#entries [data-play="song-one"]').click();
    await page.waitForFunction(()=>document.querySelector('#audio-player').currentTime>0);
    await assertNoPlaybackHistory(page);
    assert.deepEqual(errors,[]);
    console.log('Media UI passed: accessible icons and play queue, volume and mute persistence without playback history, compact folder controls, real playback, photo viewer, keyboard dismissal and RTL/large text.');
  } finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
