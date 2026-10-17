const { test } = require('node:test');
const assert = require('node:assert/strict');
const catalog = require('../ui/catalog.js');

test('all presets expose actual gradients and preserve old theme IDs', () => {
  assert.equal(catalog.themes.length, 24);
  for (const theme of catalog.themes) for (const mode of ['light','dark']) {
    assert.match(theme[mode].gradientStart, /^#[0-9a-f]{6}$/i);
    assert.match(theme[mode].gradientEnd, /^#[0-9a-f]{6}$/i);
    if (theme.group === 'color') assert.notEqual(theme[mode].gradientStart, theme[mode].gradientEnd);
  }
  for (const id of ['violet','midnight','forest','rose','sunset']) assert.equal(catalog.theme(id).id,id);
});

test('24 complete translations resolve regional aliases and preserve formatting', () => {
  assert.equal(catalog.languages.length,24);
  assert.equal(catalog.resolveLanguage('in'), 'id');
  assert.equal(catalog.resolveLanguage('system','pt-BR'), 'pt');
  assert.equal(catalog.resolveLanguage('zh-Hant-HK'), 'zh-TW');
  assert.equal(catalog.resolveLanguage('zh-SG'), 'zh-CN');
  assert.equal(catalog.resolveLanguage('fr-CA'), 'fr');
  assert.equal(catalog.resolveLanguage('unsupported'), 'en');
  for (const language of catalog.languages) {
    const messages = catalog.messages[language.code];
    assert.deepEqual(Object.keys(messages).sort(),Object.keys(catalog.messages.en).sort());
    assert.ok(catalog.text('theme_preview',language.code));
    assert.ok(!catalog.text('theme_preview',language.code).includes('???'));
  }
  assert.notEqual(catalog.text('download','fr'),catalog.text('download','en'));
  assert.equal(catalog.languages.find(l => l.code === 'fa').rtl,true);
});
