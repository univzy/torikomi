const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const script = readFileSync(join(__dirname, '../ios/Resources/gallery.js'), 'utf8');
function extract(hostname, payloads) {
  const value = vm.runInNewContext(script, {
    document: { title: 'Album', querySelectorAll: () => payloads.map(data => ({ textContent: JSON.stringify(data) })) },
    location: { hostname },
  }, { timeout: 1000 });
  return value === null ? null : JSON.parse(value);
}

test('TikTok structured photos retain order without avatars or background audio', () => {
  const result = extract('www.tiktok.com', [{ __DEFAULT_SCOPE__: { 'webapp.video-detail': { itemInfo: { itemStruct: {
    desc: 'Two photos', author: { avatarThumb: 'https://cdn.example/avatar.jpg' },
    imagePost: { images: [
      { imageURL: { urlList: ['https://cdn.example/one.jpg', 'https://cdn.example/one-backup.jpg'] } },
      { imageURL: { urlList: ['https://cdn.example/two.jpg'] } },
    ] }, music: { playUrl: 'https://cdn.example/audio.mp3' },
  } } } } }]);
  assert.deepEqual(result, { title: 'Two photos', photos: ['https://cdn.example/one.jpg', 'https://cdn.example/two.jpg'] });
});

test('Instagram carousel photos exclude videos and unrelated profile images', () => {
  const result = extract('www.instagram.com', [{ data: {
    profile_pic_url: 'https://cdn.example/avatar.jpg', xdt_shortcode_media: {
      edge_sidecar_to_children: { edges: [
        { node: { is_video: false, display_url: 'https://cdn.example/first.jpg' } },
        { node: { is_video: true, display_url: 'https://cdn.example/video-cover.jpg' } },
        { node: { is_video: false, display_url: 'https://cdn.example/last.jpg' } },
      ] },
    },
  } }]);
  assert.deepEqual(result.photos, ['https://cdn.example/first.jpg', 'https://cdn.example/last.jpg']);
});

test('Unrelated pages do not supply photos and repeated records do not duplicate downloads', () => {
  assert.equal(extract('attacker.example', [{ ItemModule: { post: { imagePost: { images: [{ imageURL: { urlList: ['https://cdn.example/a.jpg'] } }] } } } }]), null);
  const data = { data: { shortcode_media: { display_url: 'https://cdn.example/a.jpg' } } };
  assert.deepEqual(extract('instagram.com', [data, data]).photos, ['https://cdn.example/a.jpg']);
});

test('Oversized album output stops at the sentinel rejected by the native parser', () => {
  const images = Array.from({ length: 1000 }, (_, i) => ({ imageURL: { urlList: [`https://cdn.example/${i}.jpg`] } }));
  const result = extract('tiktok.com', [{ ItemModule: { post: { imagePost: { images } } } }]);
  assert.equal(result.photos.length, 101);
});
