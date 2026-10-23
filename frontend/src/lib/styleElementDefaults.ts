/**
 * 与后端 prompts/style/{画风}/character|scene|prop.md 对齐（占位提示用）。
 * 真正默认值以服务端 StylePack 为准；此处仅作创作设置 placeholder。
 */
export function defaultElementPrefix(
  stylePreset: string | undefined,
  type: 'character' | 'scene' | 'prop',
): string {
  const style = (stylePreset || '日漫赛璐璐').trim();
  const table: Record<string, Record<'character' | 'scene' | 'prop', string>> = {
    真人写实: {
      character: '真人写实，电影质感，自然光',
      scene: '真人写实，电影环境，自然光影',
      prop: '真人写实，静物质感清晰',
    },
    日漫赛璐璐: {
      character: '日漫赛璐璐，干净线稿，平涂色块',
      scene: '日漫赛璐璐，二次元场景，平涂色块',
      prop: '日漫赛璐璐，设定图，干净线稿',
    },
    韩漫清新: {
      character: '韩漫清新，干净线稿，淡彩通透',
      scene: '韩漫清新，淡彩场景，柔光通透',
      prop: '韩漫清新，设定图，流畅线稿',
    },
    国漫厚涂: {
      character: '国漫厚涂，体积笔触，柔和过渡',
      scene: '国漫厚涂，原画场景，体积光影',
      prop: '国漫厚涂，原画道具，笔触层次',
    },
    '3D CG动画': {
      character: '3D CG动画，次世代建模，立体体积',
      scene: '3D CG动画，次世代场景，电影光',
      prop: '3D CG动画，高精度建模道具',
    },
    古风水墨: {
      character: '古风水墨，写意留白，诗意东方',
      scene: '古风水墨，写意山水，留白',
      prop: '古风水墨，器物写意',
    },
    赛博朋克: {
      character: '赛博朋克，霓虹高对比，雨夜氛围',
      scene: '赛博朋克，霓虹雨夜，机能科幻',
      prop: '赛博朋克，科幻道具，霓虹反光',
    },
    电影胶片: {
      character: '电影胶片，胶片颗粒，电影光',
      scene: '电影胶片，胶片环境，柔和颗粒',
      prop: '电影胶片，静物，胶片质感',
    },
  };
  return table[style]?.[type] ?? `${style}，统一画风`;
}

export function defaultElementSuffix(type: 'character' | 'scene' | 'prop'): string {
  switch (type) {
    case 'scene':
      return '空间层次清晰，无文字水印';
    case 'prop':
      return '材质清晰，无文字水印';
    default:
      return '无文字水印';
  }
}
