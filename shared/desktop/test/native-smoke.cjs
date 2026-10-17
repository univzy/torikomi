// Run against a debug Tauri process with a temporary TORIKOMI_DATA_DIR and WebView2
// remote debugging on port 9223. Requires local yt-dlp and the generated MP4 fixture.
const { chromium } = require('@playwright/test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');

async function main() {
  const source = path.resolve(__dirname,'../../../build/native-smoke/source.mp4');
  const bytes = await fs.readFile(source);
  const server = http.createServer((req,res) => {
    const match = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    const start = match ? Number(match[1]) : 0;
    const end = match && match[2] ? Math.min(Number(match[2]),bytes.length-1) : bytes.length-1;
    res.writeHead(match ? 206 : 200,{'Content-Type':'video/mp4','Accept-Ranges':'bytes','Content-Length':end-start+1,...(match?{'Content-Range':`bytes ${start}-${end}/${bytes.length}`}:{})});
    res.end(req.method==='HEAD' ? undefined : bytes.subarray(start,end+1));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser;
  try {
    browser = await chromium.connectOverCDP('http://127.0.0.1:9223');
    const page = browser.contexts()[0].pages()[0];
    await page.waitForFunction(()=>typeof state!=='undefined'&&state);
    assert.match(page.url(),/^http:\/\/tauri\.localhost/);
    if (await page.locator('#onboarding[open]').count()) {
      await page.screenshot({path:path.resolve(__dirname,'../build/previews/native-welcome.png')});
      await page.click('#onboarding-next');await page.click('#setup-auto');
      await page.waitForFunction(()=>state.gallery && !setupBusy,{},{timeout:120000});
      assert.equal(await page.locator('#setup-finish').isDisabled(),false);
      await page.screenshot({path:path.resolve(__dirname,'../build/previews/native-setup.png')});
      await page.click('#setup-finish');await page.waitForSelector('#onboarding[open]',{state:'hidden'});
    }
    await page.click('[data-page="settings"]');
    await page.click('#color-themes [data-theme="sunset"]');await page.waitForFunction(()=>state.document.settings.value.themeColor==='sunset');
    assert.match(await page.evaluate(()=>getComputedStyle(document.body).backgroundImage),/linear-gradient/);
    await page.selectOption('#language-options','id');await page.waitForFunction(()=>document.documentElement.lang==='id');
    await page.reload();await page.waitForFunction(()=>typeof state!=='undefined'&&state);
    assert.equal(await page.evaluate(()=>state.document.settings.value.themeColor),'sunset');
    assert.equal(await page.locator('html').getAttribute('lang'),'id');
    assert.equal(await page.locator('#onboarding[open]').count(),0,'Setup completion must survive restart/reload');
    assert.equal(await page.evaluate(()=>state.setupComplete),true);
    const url=`http://127.0.0.1:${server.address().port}/source.mp4`;
    await page.fill('#url',url);await page.click('#inspect-button');
    await page.waitForFunction(()=>!inspecting,{},{timeout:60000});
    const inspection = await page.evaluate(()=>({media,error:document.getElementById('status').textContent}));
    assert.ok(inspection.media,JSON.stringify(inspection));
    assert.ok(inspection.media.formats.length>0);
    console.log('Actual yt-dlp source formats:',inspection.media.formats);
    await page.locator('.format-option input').first().check();
    await page.click('#download-button');
    await page.waitForFunction(()=>!downloading,{},{timeout:60000});
    const result=await page.evaluate(()=>({files:state.files,entries:state.document.entries,error:document.getElementById('status').textContent}));
    assert.ok(Object.keys(result.files).length>0,JSON.stringify(result));
    const saved=Object.values(result.files).at(-1);
    assert.deepEqual(await fs.readFile(saved),bytes);
    await page.screenshot({path:path.resolve(__dirname,'../build/previews/native-download.png')});
    console.log('Native Tauri smoke passed: Rust IPC, preferences persistence/reload, locale, actual gradient, real yt-dlp inspection/download, byte-identical local media.');
  } finally { if (browser) await browser.close();await new Promise(resolve=>server.close(resolve)); }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
