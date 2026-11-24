(function () {
  var out = {};
  out.title = document.title;
  out.links = Array.prototype.slice.call(document.querySelectorAll('a[href*="/movies/"], a[href*="/series/"]')).slice(0, 40).map(function (a) {
    return a.className + ' | ' + (a.getAttribute('title') || '') + ' | ' + a.href;
  });
  var cards = document.querySelectorAll('article, .item, .movie, .post, .card, li');
  out.cardCount = cards.length;
  var sample = null;
  for (var i = 0; i < cards.length; i++) {
    if (cards[i].querySelector('a[href*="/movies/"]')) { sample = cards[i]; break; }
  }
  out.cardHtml = sample ? sample.outerHTML.slice(0, 1200) : null;
  var imgs = document.querySelectorAll('img');
  out.images = Array.prototype.slice.call(imgs).slice(0, 25).map(function (i) {
    return i.className + ' | ' + i.alt + ' | ' + (i.getAttribute('data-src') || i.getAttribute('src'));
  });
  out.iframes = Array.prototype.slice.call(document.querySelectorAll('iframe')).map(function (f) {
    return f.getAttribute('src') + ' | ' + f.outerHTML.slice(0, 240);
  });
  out.scripts = Array.prototype.slice.call(document.querySelectorAll('script')).map(function (s) {
    return (s.src || 'INLINE') + ' | ' + (s.textContent || '').slice(0, 200);
  }).slice(0, 20);
  var html = document.documentElement.outerHTML;
  var hits = [];
  ['data-server', 'data-url', 'data-id', 'embed', 'player', 'servers', 'option', 'data-video',
   'wp-json', 'admin-ajax', 'nupload', 'streamtape', 'filemoon', 'vimeos', 'voe', 'dood', 'ok.ru'
  ].forEach(function (needle) {
    var index = html.indexOf(needle);
    if (index >= 0) hits.push(needle + '@' + index + ': ' + html.substr(Math.max(0, index - 60), 200).replace(/\s+/g, ' '));
  });
  out.hits = hits;
  return JSON.stringify(out);
})()
