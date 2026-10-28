(() => {
  const photoUrls = [];
  let title = '';
  const add = value => {
    if (photoUrls.length > 100) return;
    if (typeof value === 'string' && value.startsWith('https://') && !photoUrls.includes(value)) photoUrls.push(value);
  };
  const tiktok = item => {
    if (!item || !item.imagePost) return;
    title = typeof item.desc === 'string' ? item.desc.slice(0, 1000) : title;
    for (const image of item.imagePost.images || []) {
      add(image.imageURL?.urlList?.find(url => typeof url === 'string' && url.startsWith('https://')));
    }
  };
  const instagram = (item, depth = 0) => {
    if (!item || item.is_video || depth > 24 || photoUrls.length > 100) return;
    if (item.edge_sidecar_to_children) {
      for (const edge of item.edge_sidecar_to_children.edges || []) instagram(edge.node, depth + 1);
    } else if (item.carousel_media) {
      for (const child of item.carousel_media) instagram(child, depth + 1);
    } else if (item.media_type !== 2) {
      add(item.display_url || item.image_versions2?.candidates?.[0]?.url);
    }
  };
  let visited = 0;
  const findInstagram = (value, depth = 0) => {
    if (!value || typeof value !== 'object' || depth > 24 || ++visited > 20000 || photoUrls.length > 100) return;
    if (value.xdt_shortcode_media) instagram(value.xdt_shortcode_media);
    if (value.shortcode_media) instagram(value.shortcode_media);
    if (Array.isArray(value.items)) for (const item of value.items) instagram(item);
    for (const child of Object.values(value)) findInstagram(child, depth + 1);
  };
  for (const script of document.querySelectorAll('script[type="application/json"], script#__UNIVERSAL_DATA_FOR_REHYDRATION__, script#SIGI_STATE')) {
    if (script.textContent.length > 2 * 1024 * 1024) continue;
    try {
      const data = JSON.parse(script.textContent);
      if (location.hostname === 'tiktok.com' || location.hostname.endsWith('.tiktok.com')) {
        const scope = data.__DEFAULT_SCOPE__ || {};
        tiktok(scope['webapp.video-detail']?.itemInfo?.itemStruct);
        tiktok(scope['webapp.photo-detail']?.itemInfo?.itemStruct);
        for (const item of Object.values(data.ItemModule || {})) tiktok(item);
      } else if (location.hostname === 'instagram.com' || location.hostname.endsWith('.instagram.com')) {
        findInstagram(data);
      }
    } catch (_) { /* A page may include non-JSON script contents. */ }
  }
  return photoUrls.length ? JSON.stringify({ title: title || document.title.slice(0, 1000), photos: photoUrls }) : null;
})()
