import { test, expect } from './fixtures';
test('synopsis renders BBCode safely and preserves literal text', async ({ page }) => {
  await page.route('**/api/catalog/books/32', r => r.fulfill({ json: {
    eplId: 32, title: 'Example', author: 'Author', revision: 1, download: { items: [] },
    synopsis: '[b]Bold[/b] [i]Italic[/i] [sic] <img src=x onerror=alert(1)> [url=javascript:alert(1)]Unsafe[/url] [url=https://www.epublibre.org/]Safe[/url] [spoiler][list=1][*]One[*]Two[/list][/spoiler]',
  } }));
  await page.route('**/api/catalog/books/32/magnets', r => r.fulfill({ json: [] }));
  await page.goto('/catalog/32');
  const synopsis = page.locator('.synopsis');
  await expect(synopsis.locator('strong')).toHaveText('Bold');
  await expect(synopsis.locator('em')).toHaveText('Italic');
  await expect(synopsis).toContainText('[sic] <img src=x onerror=alert(1)>');
  await expect(synopsis.locator('img')).toHaveCount(0);
  await expect(synopsis.locator('a')).toHaveCount(1);
  await expect(synopsis.locator('a')).toHaveAttribute('href', 'https://www.epublibre.org/');
  await synopsis.locator('summary').click();
  await expect(synopsis.locator('ol li')).toHaveText(['One', 'Two']);
  await expect(synopsis.locator('ol')).toBeVisible();
});
