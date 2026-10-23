import '../assistant.css';
import { AssistantPanel } from './AssistantPanel';
import type { AssistantModuleProps } from '../types';

/**
 * AI 助手独立模块入口。
 * 自带右侧栏外壳，内部独立滚动，不影响工作台。
 */
export function AssistantModule({
  projectId,
  open = true,
  width = 380,
  onOpenChange,
  focus,
  onProjectChanged,
  onSourceStreaming,
  onShotsStreaming,
  onScriptsStreaming,
  onAssistantStatus,
}: AssistantModuleProps) {
  return (
    <aside
      className={`assistant-module${open ? '' : ' assistant-module--collapsed'}`}
      style={{ width: open ? width : 0 }}
      aria-label="AI 助手"
      aria-hidden={!open}
    >
      <AssistantPanel
        projectId={projectId}
        focus={focus}
        onClose={onOpenChange ? () => onOpenChange(false) : undefined}
        onProjectChanged={onProjectChanged}
        onSourceStreaming={onSourceStreaming}
        onShotsStreaming={onShotsStreaming}
        onScriptsStreaming={onScriptsStreaming}
        onAssistantStatus={onAssistantStatus}
      />
    </aside>
  );
}

export default AssistantModule;
