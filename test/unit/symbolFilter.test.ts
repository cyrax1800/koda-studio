import { describe, expect, it } from 'vitest';

import { isImportLine, lensTargets, SymbolKind, usagesTitle, type SymbolLike } from '../../src/codelens/symbolFilter.ts';

const at = (line: number, character = 0) => ({ line, character });

function symbol(name: string, kind: number, line: number, children: SymbolLike[] = [], nameColumn = 6): SymbolLike {
  return { name, kind, range: { start: at(line) }, selectionRange: { start: at(line, nameColumn) }, children };
}

const source = [
  'package demo', //                                  0
  'interface Repository {', //                        1
  '  fun load(): String', //                          2
  '}', //                                             3
  'abstract class Base {', //                         4
  '  abstract fun render()', //                       5
  '  fun helper() {}', //                             6
  '  companion object {', //                          7
  '    const val KEY = "k"', //                      8
  '  }', //                                           9
  '}', //                                             10
  'val topLevel = 1', //                              11
  'fun main() { val local = 2 }', //                  12
];
const lineAt = (line: number) => source[line] ?? '';

const symbols: SymbolLike[] = [
  symbol('Repository', SymbolKind.Interface, 1, [symbol('load', SymbolKind.Method, 2)]),
  symbol('Base', SymbolKind.Class, 4, [
    symbol('render', SymbolKind.Method, 5, [], 15),
    symbol('helper', SymbolKind.Method, 6),
    symbol('Companion', SymbolKind.Object, 7, [symbol('KEY', SymbolKind.Constant, 8, [], 14)]),
  ], 15),
  symbol('topLevel', SymbolKind.Variable, 11, [], 4),
  symbol('main', SymbolKind.Function, 12, [symbol('local', SymbolKind.Variable, 12, [], 17)], 4),
];

describe('lensTargets', () => {
  it('picks declarations, recursing only into type-like containers', () => {
    expect(lensTargets(symbols, lineAt).map((t) => `${t.container}/${t.name}`)).toEqual([
      '/Repository',
      'Repository/load',
      '/Base',
      'Base/render',
      'Base/helper',
      'Base/Companion/KEY',
      '/topLevel',
      '/main',
    ]);
  });

  it('marks interfaces and abstract/open declarations as implementable', () => {
    const implementable = lensTargets(symbols, lineAt)
      .filter((t) => t.implementable)
      .map((t) => t.name);
    expect(implementable).toEqual(['Repository', 'load', 'Base', 'render']);
  });

  it('ignores anonymous symbols', () => {
    expect(lensTargets([symbol('<anonymous>', SymbolKind.Object, 0)], lineAt)).toEqual([]);
  });
});

describe('titles and imports', () => {
  it('formats counts', () => {
    expect([0, 1, 7].map(usagesTitle)).toEqual(['no usages', '1 usage', '7 usages']);
  });

  it('recognizes import lines', () => {
    expect(isImportLine('import com.demo.Base')).toBe(true);
    expect(isImportLine('  import com.demo.Base as B')).toBe(true);
    expect(isImportLine('val important = Base()')).toBe(false);
  });
});
