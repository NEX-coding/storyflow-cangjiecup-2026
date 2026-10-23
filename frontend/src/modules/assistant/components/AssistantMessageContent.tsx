import type { ReactNode } from 'react';

interface Props {
  content: string;
  streaming?: boolean;
}

/** 简易 markdown：代码块 + 行内代码 */
export function AssistantMessageContent({ content, streaming }: Props) {
  if (!content && streaming) {
    return <span className="assistant-stream-cursor" />;
  }

  const parts = content.split(/(```[\s\S]*?```)/g);
  const nodes: ReactNode[] = [];

  parts.forEach((part, i) => {
    if (part.startsWith('```') && part.endsWith('```')) {
      const inner = part.slice(3, -3);
      const firstNewline = inner.indexOf('\n');
      const langLine = firstNewline >= 0 ? inner.slice(0, firstNewline).trim() : '';
      const code = firstNewline >= 0 ? inner.slice(firstNewline + 1) : inner;
      const filename = langLine.includes('.') ? langLine : undefined;
      const lang = filename ? extToLang(filename) : langLine || 'text';

      nodes.push(
        <div key={i} className="assistant-code-block">
          {(filename || lang !== 'text') && (
            <div className="assistant-code-block__header">
              <span className={`assistant-code-block__icon assistant-code-block__icon--${lang}`}>
                {langIcon(lang)}
              </span>
              <span className="assistant-code-block__filename">
                {filename || lang}
              </span>
            </div>
          )}
          <pre
            className="assistant-code-block__body"
            dangerouslySetInnerHTML={{ __html: highlightBasic(code.trimEnd()) }}
          />
        </div>,
      );
      return;
    }

    if (part) {
      nodes.push(
        <span key={i} className="assistant-assistant-msg__content">
          {renderInline(part)}
        </span>,
      );
    }
  });

  return (
    <>
      {nodes}
      {streaming && <span className="assistant-stream-cursor" />}
    </>
  );
}

function renderInline(text: string): ReactNode {
  const segments = text.split(/(`[^`]+`)/g);
  return segments.map((seg, i) => {
    if (seg.startsWith('`') && seg.endsWith('`')) {
      return (
        <code key={i} className="assistant-inline-code">
          {seg.slice(1, -1)}
        </code>
      );
    }
    return seg;
  });
}

function extToLang(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() ?? '';
  const map: Record<string, string> = {
    java: 'java',
    ts: 'ts',
    tsx: 'ts',
    js: 'ts',
    jsx: 'ts',
    py: 'default',
    css: 'default',
    md: 'default',
  };
  return map[ext] ?? 'default';
}

function langIcon(lang: string): string {
  if (lang === 'java') return 'J';
  if (lang === 'ts') return 'TS';
  return lang.slice(0, 2).toUpperCase();
}

/** 轻量关键字高亮，适配深色代码块 */
function highlightBasic(code: string): string {
  const escaped = code
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  return escaped.replace(
    /\b(private|public|protected|static|final|class|interface|record|enum|void|return|import|package|const|let|var|function|async|await|new|if|else|for|while|try|catch|throw|extends|implements|type|interface)\b/g,
    '<span class="kw">$1</span>',
  ).replace(
    /\b([A-Z][A-Za-z0-9_]*)\b/g,
    '<span class="type">$1</span>',
  );
}
