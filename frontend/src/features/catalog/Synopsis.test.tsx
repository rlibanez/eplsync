import { expect, it } from "vitest";
import { parseSynopsis, safeSynopsisUrl } from "./Synopsis";
it("parses nested formatting and numbered list markers", () => {
  const nodes = parseSynopsis('[spoiler][list=1][*][b]Title[/b][*][i]Other[/i][/list][/spoiler]');
  expect(JSON.stringify(nodes)).toContain('"tag":"spoiler"');
  expect(JSON.stringify(nodes)).toContain('"argument":"1"');
  expect(JSON.stringify(nodes).match(/"tag":"item"/g)).toHaveLength(2);
});
it("preserves unknown brackets and unfinished markup", () => {
  expect(parseSynopsis('Text [sic] [b]unfinished [i]word[/i]').join('')).toBe('Text [sic] [b]unfinished [i]word[/i]');
});
it("only allows absolute HTTP and HTTPS links", () => {
  expect(safeSynopsisUrl('https://epublibre.org/libro/detalle/32')).toBe('https://epublibre.org/libro/detalle/32');
  for (const value of ['javascript:alert(1)', 'data:text/html,test', '//evil.test', '/relative']) expect(safeSynopsisUrl(value)).toBeUndefined();
});
