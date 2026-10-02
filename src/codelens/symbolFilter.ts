// Chooses which document symbols get a usages lens. Kept free of the vscode module so it can be unit-tested.

/** Values of vscode.SymbolKind. */
export const SymbolKind = {
  File: 0,
  Module: 1,
  Namespace: 2,
  Package: 3,
  Class: 4,
  Method: 5,
  Property: 6,
  Field: 7,
  Constructor: 8,
  Enum: 9,
  Interface: 10,
  Function: 11,
  Variable: 12,
  Constant: 13,
  Object: 18,
  EnumMember: 21,
  Struct: 22,
  TypeParameter: 25,
} as const;

export interface PositionLike {
  line: number;
  character: number;
}

export interface SymbolLike {
  name: string;
  kind: number;
  range: { start: PositionLike };
  selectionRange: { start: PositionLike };
  children?: SymbolLike[];
}

export interface LensTarget {
  name: string;
  kind: number;
  /** Names of the enclosing declarations, e.g. `HomeViewModel/Companion`; part of the cache identity. */
  container: string;
  position: PositionLike;
  /** Whether an "implementations" lens makes sense (interfaces, abstract/open/sealed declarations). */
  implementable: boolean;
}

const CONTAINERS = new Set<number>([
  SymbolKind.File,
  SymbolKind.Module,
  SymbolKind.Namespace,
  SymbolKind.Package,
  SymbolKind.Class,
  SymbolKind.Interface,
  SymbolKind.Enum,
  SymbolKind.Struct,
  SymbolKind.Object,
]);

const TYPES = new Set<number>([SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum, SymbolKind.Struct, SymbolKind.Object]);

const MEMBERS = new Set<number>([SymbolKind.Method, SymbolKind.Function, SymbolKind.Property, SymbolKind.Field, SymbolKind.Constant]);

const OPEN_MODIFIER = /\b(abstract|open|sealed)\b/;

/**
 * @param lineAt text of a line in the document, used to read `abstract`/`open`/`sealed` modifiers.
 */
export function lensTargets(symbols: SymbolLike[], lineAt: (line: number) => string): LensTarget[] {
  const targets: LensTarget[] = [];
  const visit = (symbol: SymbolLike, container: string[], parentKind: number | undefined) => {
    if (symbol.name.startsWith('<')) return;
    const companion = symbol.kind === SymbolKind.Object && symbol.name === 'Companion';
    const topLevel = container.length === 0;
    const isTarget =
      !companion &&
      (TYPES.has(symbol.kind) || MEMBERS.has(symbol.kind) || (symbol.kind === SymbolKind.Variable && topLevel));
    if (isTarget) {
      const declaration = declarationText(symbol, lineAt);
      const implementable =
        symbol.kind === SymbolKind.Interface ||
        (TYPES.has(symbol.kind) && OPEN_MODIFIER.test(declaration)) ||
        (MEMBERS.has(symbol.kind) && (parentKind === SymbolKind.Interface || /\b(abstract|open)\b/.test(declaration)));
      targets.push({
        name: symbol.name,
        kind: symbol.kind,
        container: container.join('/'),
        position: symbol.selectionRange.start,
        implementable,
      });
    }
    if (CONTAINERS.has(symbol.kind)) {
      for (const child of symbol.children ?? []) visit(child, [...container, symbol.name], symbol.kind);
    }
  };
  for (const symbol of symbols) visit(symbol, [], undefined);
  return targets;
}

/** Text from the start of the declaration (annotations and modifiers) up to the symbol's name. */
function declarationText(symbol: SymbolLike, lineAt: (line: number) => string): string {
  const first = symbol.range.start.line;
  const last = symbol.selectionRange.start.line;
  const lines: string[] = [];
  for (let line = first; line <= last && line - first < 20; line++) {
    const text = lineAt(line);
    lines.push(line === last ? text.slice(0, symbol.selectionRange.start.character) : text);
  }
  return lines.join('\n');
}

export function usagesTitle(count: number): string {
  if (count === 0) return 'no usages';
  return count === 1 ? '1 usage' : `${count} usages`;
}

export function implementationsTitle(count: number): string {
  if (count === 0) return 'no implementations';
  return count === 1 ? '1 implementation' : `${count} implementations`;
}

export function isImportLine(text: string): boolean {
  return /^\s*import\s/.test(text);
}
