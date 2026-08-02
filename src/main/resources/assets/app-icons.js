'use strict';
(function () {
  const ROOT = '/assets/icons/generated/';
  const files = Object.freeze({
    brand: 'brand-chat.png',
    chat: 'brand-chat.png',
    contacts: 'contacts.png',
    moments: 'moments.png',
    miniapps: 'miniapps.png',
    games: 'miniapps.png',
    cloud: 'cloud.png',
    ai: 'ai.png',
    music: 'music.png',
    video: 'video.png',
    videos: 'video.png',
    notes: 'notes.png',
    profile: 'profile.png',
    admin: 'admin.png',
    feedback: 'feedback.png',
    qr: 'qr.png',
    categoryTools: 'category-tools.png',
    categoryGames: 'category-games.png',
    categoryStudy: 'category-study.png',
    categoryLife: 'category-life.png',
    categoryEntertainment: 'category-entertainment.png',
    categoryOther: 'category-other.png',
    emptyMessages: 'empty-messages.png',
    emptyFiles: 'empty-files.png',
    emptyVideos: 'empty-videos.png',
    emptyNotes: 'empty-notes.png'
  });

  const paths = {};
  Object.keys(files).forEach(function (key) {
    paths[key] = ROOT + files[key];
  });
  window.AppIcons = Object.freeze(paths);

  function escapeAttr(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  window.featureIcon = function featureIcon(key, alt) {
    const src = window.AppIcons[key] || window.AppIcons.brand;
    return '<img class="feature-icon-image" src="' + escapeAttr(src) + '" alt="' +
      escapeAttr(alt || '') + '" width="40" height="40" loading="lazy" decoding="async">';
  };

  window.featureIconPath = function featureIconPath(key) {
    return window.AppIcons[key] || window.AppIcons.brand;
  };

  window.emptyState = function emptyState(iconKey, message, className) {
    const extraClass = String(className || '').replace(/[^a-zA-Z0-9_-]/g, ' ').trim();
    return '<div class="empty-state illustrated-empty' + (extraClass ? ' ' + extraClass : '') +
      '" data-empty-icon="' + escapeAttr(iconKey || '') + '">' +
      window.featureIcon(iconKey || 'brand', '') +
      '<span>' + escapeAttr(message || '') + '</span></div>';
  };

  const themeAliases = Object.freeze({
    '': 'sand',
    default: 'sand',
    sand: 'sand',
    light: 'sand',
    dark: 'ink',
    ink: 'ink',
    night: 'ink',
    green: 'pine',
    tea: 'pine',
    blue: 'pine',
    pine: 'pine',
    pink: 'clay',
    orange: 'clay',
    red: 'clay',
    rgb: 'clay',
    clay: 'clay'
  });

  window.normalizeTheme = function normalizeTheme(storedTheme) {
    const key = String(storedTheme == null ? '' : storedTheme).trim().toLowerCase().replace(/^t-/, '');
    return themeAliases[key] || 'sand';
  };
})();
