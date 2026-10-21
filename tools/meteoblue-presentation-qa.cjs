// Live provider integration check. Uses Chrome and an external Playwright installation.
const {chromium} = require(process.env.HORIZON_PLAYWRIGHT || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'signing/meteoblue-1.0.5-qa');
const presentation = fs.readFileSync(path.join(root, 'app/src/main/assets/meteoblue-presentation.js'), 'utf8');

(async () => {
  fs.mkdirSync(output, {recursive: true});
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  const results = [];
  try {
    for (const [city, coordinates] of [['anapa', '44.894970N37.316230E'], ['petersburg', '59.938600N30.314100E']]) {
      for (const period of ['day', 'week']) {
        const type = period === 'day' ? 'three' : 'daily';
        const page = await browser.newPage({viewport: {width: 810, height: 260}});
        const response = await page.goto('https://www.meteoblue.com/ru/weather/widget/' + type + '/' + coordinates
          + '?geoloc=fixed&days=' + (period === 'day' ? 4 : 7)
          + '&coloured=monochrome&layout=dark&tempunit=CELSIUS&windunit=METER_PER_SECOND'
          + '&pictoicon=1&maxtemperature=1&mintemperature=1&windspeed=0&windgust=0'
          + '&winddirection=0&uv=0&humidity=0&precipitation=0&precipitationprobability=0&spot=0&pressure=0',
          {waitUntil: 'networkidle', timeout: 45000});
        assert.equal(response.status(), 200);
        const selector = period === 'day' ? '#hourly > .day-hourly:first-child table' : '.picto .temp';
        const values = await page.locator(selector).allTextContents();
        const heightBefore = await page.locator('body').evaluate(n => n.scrollHeight);
        assert.equal(await page.evaluate('(' + presentation + ')(' + JSON.stringify(period) + ')'), true);
        assert.deepEqual(await page.locator(selector).allTextContents(), values, 'Forecast values changed');
        const heightAfter = await page.locator('body').evaluate(n => n.scrollHeight);
        if (period === 'day') {
          assert.equal(await page.locator('#hourly > .day-hourly:visible').count(), 1);
          assert.equal(await page.locator('#locationform:visible').count(), 0);
          assert.equal(await page.locator('.forecast > .daily:visible').count(), 0);
          assert.equal(await page.locator('.wrapper > header .logo:visible').count(), 0);
          assert.equal(await page.locator('.wrapper > footer a:visible').count(), 1);
          assert.ok(heightAfter < heightBefore * .7, 'Daily content not compact enough');
        } else {
          assert.equal(await page.locator('body > header:visible').count(), 0);
          assert.equal(await page.locator('.picto .day:visible').count(), 7);
          assert.equal(await page.locator('.picto .temp:visible').count(), 14);
        }
        const screenshot = path.join(output, city + '-' + period + '.png');
        await page.screenshot({path: screenshot, fullPage: true, omitBackground: true});
        // Reapplying after a duplicate page-finished callback must be harmless.
        assert.equal(await page.evaluate('(' + presentation + ')(' + JSON.stringify(period) + ')'), true);
        await page.setContent('<p>Provider layout unavailable</p>');
        assert.equal(await page.evaluate('(' + presentation + ')(' + JSON.stringify(period) + ')'), false);
        await page.goto('about:blank');
        assert.equal(await page.evaluate('(' + presentation + ')("day")'), false);
        results.push({city, period, heightBefore, heightAfter, forecastValuesUnchanged: true,
          coordinatesHidden: true, expectedPeriodVisible: true, missingLayoutRejected: true,
          foreignPageRejected: true, screenshot});
        await page.close();
      }
    }
  } finally {
    await browser.close();
  }
  fs.writeFileSync(path.join(output, 'browser.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify(results));
})().catch(error => {console.error(error.message); process.exit(1);});
