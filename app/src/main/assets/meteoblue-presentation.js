function (period) {
  'use strict';
  const type = period === 'day' ? 'three' : period === 'week' ? 'daily' : null;
  if (!type || location.protocol !== 'https:' || location.hostname !== 'www.meteoblue.com'
      || !location.pathname.startsWith('/ru/weather/widget/' + type + '/')) return false;

  if (period === 'day') {
    const today = document.querySelector('#hourly > .day-hourly:first-child');
    if (!today || !today.querySelector('tr.times') || !today.querySelector('tr.temperatures')
        || !document.querySelector('.wrapper > header .logo')
        || !document.querySelector('.wrapper > footer a')) return false;
  } else if (document.querySelectorAll('.picto .day').length !== 7) return false;

  let style = document.getElementById('horizon-weather-presentation');
  if (!style) {
    style = document.createElement('style');
    style.id = 'horizon-weather-presentation';
    document.head.appendChild(style);
  }
  style.textContent = `
    html[data-horizon-weather="week"] body > header { display: none !important; }
    html[data-horizon-weather="day"] #locationform,
    html[data-horizon-weather="day"] .forecast > .daily,
    html[data-horizon-weather="day"] #hourly > .day-hourly:not(:first-child) {
      display: none !important;
    }
    html[data-horizon-weather="day"] #hourly > .day-hourly:first-child { display: block !important; }
    html[data-horizon-weather="day"] .wrapper { padding-top: 8px !important; padding-bottom: 4px !important; }
    html[data-horizon-weather="day"] .wrapper > header.header { display: none !important; }
    html[data-horizon-weather="day"] .current-weather { padding: 0 !important; margin: 0 !important; }
    html[data-horizon-weather="day"] .current-weather-wrapper {
      display: flex !important; align-items: center !important; gap: 16px;
    }
    html[data-horizon-weather="day"] .current-temp {
      font-size: 28px !important; line-height: 1.2 !important;
    }
    html[data-horizon-weather="day"] .current-temp .temp-value { font-size: inherit !important; }
    html[data-horizon-weather="day"] .current-temp .temp-unit { font-size: 16px !important; }
    html[data-horizon-weather="day"] .current-misc { padding: 0 !important; }
    html[data-horizon-weather="day"] .wrapper > footer {
      padding: 0 !important; margin: 0 !important; min-height: 0 !important;
    }
    html[data-horizon-weather="day"] .wrapper > footer .more {
      height: auto !important; font-size: 12px !important; line-height: 16px !important;
      padding: 2px 8px !important;
    }
  `;
  document.documentElement.setAttribute('data-horizon-weather', period);
  window.scrollTo(0, 0);
  return true;
}
