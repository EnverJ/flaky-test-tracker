# Flaky test fix patterns

Root cause → evidence → fix, for Selenium (Java / Python) and Playwright (TS / Python).
Always adapt to the project's own helpers and page-object style. Each pattern lists
the **anti-fix** (what not to do) because that's what most "fixes" actually are.

---

## 1. Synchronization / timing

**Evidence:** `TimeoutException`, `NoSuchElementException`, `ElementNotInteractableException`,
`Timeout 30000ms exceeded`, failures cluster on slow CI runs, test has `sleep`.

**Anti-fix:** longer sleep, bigger global timeout, implicit wait.

**Selenium (Java)** — wait for the *condition*, not time:
```java
// before
Thread.sleep(3000);
driver.findElement(By.id("submit")).click();

// after
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
wait.until(ExpectedConditions.elementToBeClickable(By.id("submit"))).click();
```
Put this in one place (BasePage `click(By)`, `type(By, String)`, `textOf(By)`) so every
page object gets it. Set `implicitlyWait(Duration.ZERO)` when using explicit waits.

**Selenium (Python):**
```python
WebDriverWait(driver, 10).until(EC.element_to_be_clickable((By.ID, "submit"))).click()
```

**Playwright:** actions already auto-wait; remove `waitForTimeout`, wait for the
real signal:
```ts
// before
await page.click('#save');
await page.waitForTimeout(2000);
expect(await page.locator('.toast').isVisible()).toBe(true);

// after
const saved = page.waitForResponse(r => r.url().includes('/api/items') && r.request().method() === 'POST');
await page.getByRole('button', { name: 'Save' }).click();
await saved;
await expect(page.getByRole('status')).toHaveText('Saved');
```

---

## 2. Non-retrying assertions

**Evidence:** assertion fails with the *previous* value (e.g. expected "3 items", got "2 items").

**Playwright:** use web-first assertions (they retry until timeout).
| One-shot (flaky) | Web-first (stable) |
|---|---|
| `expect(await loc.isVisible()).toBe(true)` | `await expect(loc).toBeVisible()` |
| `expect(await loc.textContent()).toBe('x')` | `await expect(loc).toHaveText('x')` |
| `expect(await loc.count()).toBe(3)` | `await expect(loc).toHaveCount(3)` |
| `expect(page.url()).toContain('/home')` | `await expect(page).toHaveURL(/\/home/)` |
| `expect(await loc.getAttribute('aria-checked')).toBe('true')` | `await expect(loc).toHaveAttribute('aria-checked', 'true')` |

For non-UI values use `await expect.poll(() => api.getStatus(id)).toBe('DONE')`.

**Selenium:** wait for the expected state, then assert:
```java
wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector(".cart-count"), "3"));
```

---

## 3. Stale element / DOM re-render

**Evidence:** `StaleElementReferenceException`, `element is not attached to the DOM`,
happens after a filter/sort/save that re-renders a list.

**Anti-fix:** try/catch retry loop around the click.

**Selenium:** don't hold `WebElement` references across actions that re-render; keep
`By` locators in page objects and re-find. Wait for the re-render to finish:
```java
WebElement oldRow = driver.findElement(rows);
driver.findElement(sortByPrice).click();
wait.until(ExpectedConditions.stalenessOf(oldRow));
List<WebElement> sorted = driver.findElements(rows);
```

**Playwright:** use `Locator`s (lazily re-resolved), never `ElementHandle` / `page.$()`.

---

## 4. Click intercepted / overlays / animations

**Evidence:** `ElementClickInterceptedException`, `<div class="spinner"> intercepts pointer events`,
`element is not stable`.

**Anti-fix:** JS click, `force: true`.

**Selenium:**
```java
wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector(".loading-overlay")));
wait.until(ExpectedConditions.elementToBeClickable(saveBtn)).click();
```
**Playwright:**
```ts
await expect(page.locator('.loading-overlay')).toBeHidden();
await page.getByRole('button', { name: 'Save' }).click();
```
Disable animations in test env when possible (`reducedMotion: 'reduce'` in Playwright
`use`, or a CSS `* { transition: none !important; animation: none !important; }` injected
in a fixture). Cookie banners/modals: dismiss them in a fixture, or set the consent
cookie before navigation.

---

## 5. Brittle / ambiguous locators

**Evidence:** `strict mode violation: ... resolved to 2 elements`, wrong row clicked,
absolute XPath, `.nth(2)`, auto-generated class names (`css-1x2y3z`).

**Fix order of preference:** role + accessible name → `data-testid` → label/placeholder →
stable id → scoped CSS. Scope to a container instead of indexing:
```ts
// before
await page.locator('table tr').nth(3).locator('button').click();
// after
await page.getByRole('row', { name: /Order #1042/ }).getByRole('button', { name: 'Cancel' }).click();
```
```java
By cancelFor(String orderId) {
  return By.cssSelector("[data-testid='order-row-" + orderId + "'] [data-testid='cancel']");
}
```
If the app has no good hooks, recommend adding `data-testid` attributes (a small dev ask
that removes a whole class of flakes).

---

## 6. Missing `await` / async misuse (Playwright)

**Evidence:** `Target page, context or browser has been closed`, assertions pass/fail
randomly, actions appear to happen in the wrong order, ESLint `no-floating-promises`.

**Fix:** add `await` on every Playwright action/assertion; enable
`@typescript-eslint/no-floating-promises` and `playwright/missing-playwright-await`
(eslint-plugin-playwright) so it can't come back. Watch `forEach(async ...)` — use
`for...of` with `await` instead.

---

## 7. Shared state / order dependency

**Evidence:** passes when run alone, fails in suite or in parallel; `dependsOnMethods`,
`describe.serial`, static fields, a "login" test that later tests rely on.

**Selenium (TestNG parallel)** — per-thread driver:
```java
public final class DriverManager {
  private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();
  public static WebDriver get() { return DRIVER.get(); }
  public static void start() { DRIVER.set(new ChromeDriver()); }
  public static void quit() { WebDriver d = DRIVER.get(); if (d != null) { d.quit(); DRIVER.remove(); } }
}
// @BeforeMethod -> DriverManager.start(); @AfterMethod(alwaysRun = true) -> DriverManager.quit();
```
Also check: static non-final fields in tests/page objects, singletons caching pages,
`@BeforeClass` setup that later tests mutate.

**Playwright:** each test gets a fresh context by default — flakes here usually come from
shared *backend* data or `describe.serial`. Move preconditions into fixtures:
```ts
export const test = base.extend<{ order: Order }>({
  order: async ({ request }, use) => {
    const order = await createOrderViaApi(request);   // fresh data per test
    await use(order);
    await deleteOrderViaApi(request, order.id);
  },
});
```
Auth: reuse `storageState`, but give each worker its own account if tests mutate the
account (`testInfo.parallelIndex`).

---

## 8. Test data collisions

**Evidence:** "already exists", unique-constraint errors, a list contains another test's
rows, counts off by one in parallel.

**Fix:** unique data per test (`order-${testInfo.workerIndex}-${Date.now()}` /
`UUID.randomUUID()`), create via API in setup, clean up in teardown, assert on
*your* record rather than total counts.

---

## 9. Network / backend dependency

**Evidence:** `net::ERR_*`, 5xx in trace, slow third-party scripts (analytics, maps,
payment widgets), failures correlate with time of day.

**Fix:** wait for the specific response the UI depends on; mock third parties and
non-target services:
```ts
await page.route('**/analytics/**', r => r.abort());
await page.route('**/api/feature-flags', r => r.fulfill({ json: { newCheckout: true } }));
```
Selenium 4: use the BiDi/CDP network interception, or a stub server / WireMock for
backend services. If the system under test itself is unstable, report it — that's an
environment/product issue, not a test fix.

---

## 10. Environment differences

**Evidence:** fails only in CI/headless, only on one browser, around midnight / month end,
only in another timezone or locale.

**Fixes:**
- Pin viewport and device scale (`viewport`, `--window-size=1920,1080`); responsive
  layouts hide elements at smaller sizes.
- Pin `timezoneId` / `locale` (Playwright) or `TZ` / `-Duser.timezone` (Java).
- Freeze time: Playwright `page.clock.setFixedTime(...)`; inject a `Clock` in Java code.
- CI resource starvation: reduce workers, check CPU limits; don't raise timeouts first.

---

## 11. Product race (real bug)

**Evidence:** the same failure can be reproduced manually by acting quickly (double click,
navigate during save), or responses arrive out of order.

**Action:** don't make the test tolerate it. Write it up: steps, trace/video, frequency
from `FlakyScore`, suspected component. Quarantine the test only with a linked ticket.

---

## Quarantine template (temporary, never silent)

```ts
// QUARANTINED 2026-10-08 — flaky 4/20 runs — root cause: product race in cart API — ticket: QA-123 — owner: @name
test.fixme('adds item to cart', async ({ page }) => { ... });
```
```java
// QUARANTINED — flaky 4/20 — ticket QA-123 — owner @name
@Test(groups = "quarantine")
```
Run the quarantine group in a separate non-blocking CI job so the data keeps flowing.

---

## Prevention checklist (for the report)
- CI: Playwright `--fail-on-flaky-tests` or track `flaky` status; Surefire `rerunFailingTestsCount`
  + parse `<flakyFailure>` instead of hiding it.
- Lint: eslint-plugin-playwright (`no-wait-for-timeout`, `no-force-option`,
  `prefer-web-first-assertions`, `missing-playwright-await`); a Checkstyle/PMD rule or
  grep gate banning `Thread.sleep` under `src/test`.
- Weekly scheduled run of `java scripts/FlakyScore.java --history` to spot new flakes and trends.
- New tests: run `--repeat-each=10` (or `java scripts/CollectRuns.java -n 10`) before merge.
