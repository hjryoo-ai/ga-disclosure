// 화면 원천 판독(시험 전용): TypeScript 구문 트리로 사람에게 보일 수 있는 텍스트 조각을 꺼낸다. literalScan·screenLogic 시험이 같이 쓴다.
import ts from 'typescript';

/** 원천 하나의 사람에게 보일 수 있는 텍스트 조각: 문자열·템플릿 조각·JSX 텍스트·정규식. 주석은 구문 트리에 없다. */
export function literals(fileName: string, text: string): string[] {
  const kind = fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const source = ts.createSourceFile(fileName, text, ts.ScriptTarget.Latest, true, kind);
  const out: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateHead(node) || ts.isTemplateMiddle(node)
      || ts.isTemplateTail(node) || ts.isRegularExpressionLiteral(node)) {
      out.push(node.text);
    } else if (ts.isJsxText(node)) {
      if (node.text.trim() !== '') out.push(node.text);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return out;
}
