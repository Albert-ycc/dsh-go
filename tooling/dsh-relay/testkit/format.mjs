// 夹具期望输出的统一格式：缩进 2 空格，数字数组（bounds）保持单行。
export function formatGolden(v) {
  return (
    JSON.stringify(v, null, 2).replace(/\[\s*(-?\d+(?:,\s*-?\d+)*)\s*\]/g, (_, g) => `[${g.replace(/\s+/g, '')}]`) + '\n'
  );
}
