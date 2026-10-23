import { useEffect, useRef, useState } from 'react';
import { Button, Dropdown, Input, Modal, Select, message } from 'antd';
import type { InputRef } from 'antd/es/input';
import {
  CloseOutlined,
  DownloadOutlined,
  ExperimentOutlined,
  FileMarkdownOutlined,
  FileTextOutlined,
  HistoryOutlined,
  MenuOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { projectApi, workspaceApi } from '../../api/client';
import { StylePresetPicker } from '../StylePresetPicker';
import { clearOpenWorkspaceId } from '../../lib/openWorkspace';
import {
  STYLE_PRESETS,
  loadUserSettings,
  normalizeStylePreset,
  type ExportFormat,
} from '../../lib/userSettings';
import {
  buildExportMarkdown,
  downloadTextFile,
  exportFilename,
} from '../../lib/exportProject';
import type { Project } from '../../types';

interface WorkspaceHeaderProps {
  project: Project | null;
  readOnly?: boolean;
  searchQuery: string;
  onSearchChange: (value: string) => void;
  assistantOpen: boolean;
  onAssistantToggle: () => void;
  onNavToggle: () => void;
  onStyleChange: (stylePreset: string) => Promise<void>;
  onProjectRenamed?: (project: Project) => void;
  createOpen: boolean;
  onCreateOpenChange: (open: boolean) => void;
  onOpenHistory?: () => void;
}

export function WorkspaceHeader({
  project,
  readOnly = false,
  searchQuery,
  onSearchChange,
  assistantOpen,
  onAssistantToggle,
  onNavToggle,
  onStyleChange,
  onProjectRenamed,
  createOpen,
  onCreateOpenChange,
  onOpenHistory,
}: WorkspaceHeaderProps) {
  const navigate = useNavigate();
  const searchRef = useRef<HTMLInputElement>(null);
  const renameInputRef = useRef<InputRef>(null);
  const [createTitle, setCreateTitle] = useState('');
  const [createStylePreset, setCreateStylePreset] = useState(() => loadUserSettings().defaultStylePreset);
  const [creating, setCreating] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameTitle, setRenameTitle] = useState('');
  const [renaming, setRenaming] = useState(false);
  const [exporting, setExporting] = useState(false);
  const defaultExportFormat = loadUserSettings().defaultExportFormat;

  useEffect(() => {
    if (createOpen) {
      setCreateStylePreset(loadUserSettings().defaultStylePreset);
    }
  }, [createOpen]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchRef.current?.focus();
        searchRef.current?.select();
      }
      if (e.key === 'Escape' && document.activeElement === searchRef.current) {
        onSearchChange('');
        searchRef.current?.blur();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onSearchChange]);

  const createProject = async () => {
    if (!createTitle.trim()) return;
    setCreating(true);
    try {
      const created = await projectApi.create(createTitle.trim(), createStylePreset);
      message.success('项目已创建');
      onCreateOpenChange(false);
      setCreateTitle('');
      navigate(`/projects/${created.id}`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const createDemoProject = async () => {
    setCreating(true);
    try {
      const created = await projectApi.createDemo();
      message.success('示例项目已创建');
      onCreateOpenChange(false);
      navigate(`/projects/${created.id}`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const openRenameModal = () => {
    if (!project) return;
    setRenameTitle(project.title);
    setRenameOpen(true);
  };

  useEffect(() => {
    if (renameOpen) {
      requestAnimationFrame(() => {
        renameInputRef.current?.focus();
        renameInputRef.current?.select();
      });
    }
  }, [renameOpen]);

  const submitRename = async () => {
    if (!project || !renameTitle.trim()) return;
    setRenaming(true);
    try {
      const updated = await projectApi.update(project.id, { title: renameTitle.trim() });
      message.success('已重命名');
      setRenameOpen(false);
      setRenameTitle('');
      onProjectRenamed?.(updated);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重命名失败');
    } finally {
      setRenaming(false);
    }
  };

  const exportProject = async (format: ExportFormat) => {
    if (!project) return;
    setExporting(true);
    try {
      const data = await workspaceApi.export(project.id);
      const title = data.project?.title || project.title || 'storyflow';
      if (format === 'markdown') {
        downloadTextFile(
          exportFilename(title, 'markdown'),
          buildExportMarkdown(data),
          'text/markdown;charset=utf-8',
        );
        message.success('已导出 Markdown 提示词包');
      } else {
        downloadTextFile(
          exportFilename(title, 'json'),
          `${JSON.stringify(data, null, 2)}\n`,
          'application/json;charset=utf-8',
        );
        message.success('已导出 JSON 提示词包');
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    } finally {
      setExporting(false);
    }
  };

  const shotCount = project?.shotCount ?? 0;

  return (
    <>
      <div className="workspace-header">
        <div className="workspace-header-start">
          <button
            type="button"
            className="workspace-nav-toggle"
            aria-label="展开导航"
            onClick={onNavToggle}
          >
            <MenuOutlined />
          </button>

          <span className="workspace-header-divider" aria-hidden />

          <div className="workspace-header-search">
            <SearchOutlined className="workspace-header-search-icon" />
            <input
              ref={searchRef}
              className="workspace-header-search-input"
              placeholder="搜索分镜、人物、台词 (⌘K)"
              value={searchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
            />
            {searchQuery && (
              <button
                type="button"
                className="workspace-header-search-clear"
                title="清除搜索"
                onClick={() => onSearchChange('')}
              >
                <CloseOutlined />
              </button>
            )}
          </div>

          {project && (
            <div className="workspace-project-chip">
              <button
                type="button"
                className="workspace-project-chip-main"
                title={`${project.title}（双击重命名）`}
                onDoubleClick={openRenameModal}
              >
                <span className="workspace-project-chip-title">{project.title}</span>
                <span className="workspace-project-chip-meta">{shotCount} 镜</span>
              </button>
              <button
                type="button"
                className="workspace-project-chip-close"
                title="返回作品列表"
                onClick={() => {
                  clearOpenWorkspaceId();
                  navigate('/projects');
                }}
              >
                <CloseOutlined />
              </button>
            </div>
          )}

          <button
            type="button"
            className="workspace-new-project-btn"
            onClick={() => onCreateOpenChange(true)}
          >
            <PlusOutlined />
            <span className="workspace-new-project-label">新建项目</span>
          </button>
        </div>

        <div className="workspace-header-actions">
          <Select
            value={project ? normalizeStylePreset(project.stylePreset) : undefined}
            className="workspace-style-select"
            size="middle"
            options={STYLE_PRESETS.map((p) => ({ value: p.value, label: p.label }))}
            onChange={(v) => onStyleChange(v)}
            disabled={!project || readOnly}
          />

          <Button
            icon={<HistoryOutlined />}
            disabled={!project}
            onClick={onOpenHistory}
            title="版本快照与回滚"
          >
            历史
          </Button>

          <Dropdown.Button
            className="workspace-export-btn"
            type="primary"
            icon={<DownloadOutlined />}
            loading={exporting}
            disabled={!project}
            onClick={() => void exportProject(defaultExportFormat)}
            menu={{
              items: [
                {
                  key: 'markdown',
                  icon: <FileMarkdownOutlined />,
                  label: '导出 Markdown',
                  onClick: () => void exportProject('markdown'),
                },
                {
                  key: 'json',
                  icon: <FileTextOutlined />,
                  label: '导出 JSON',
                  onClick: () => void exportProject('json'),
                },
              ],
            }}
          >
            导出提示词包
          </Dropdown.Button>

          <Button
            type={assistantOpen ? 'primary' : 'default'}
            icon={<RobotOutlined />}
            onClick={onAssistantToggle}
            disabled={readOnly}
          >
            AI
          </Button>
        </div>
      </div>

      <Modal
        className="home-modal home-modal--wide"
        title="新建项目"
        open={createOpen}
        onOk={createProject}
        onCancel={() => onCreateOpenChange(false)}
        okText="创建"
        cancelText="取消"
        confirmLoading={creating}
        width={640}
      >
        <div style={{ marginBottom: 12 }}>
          <button
            type="button"
            className="home-hero-cta"
            style={{ padding: '6px 16px', fontSize: 13 }}
            disabled={creating}
            onClick={createDemoProject}
          >
            <ExperimentOutlined /> 快速体验示例项目
          </button>
        </div>
        <Input
          placeholder="项目名称"
          value={createTitle}
          onChange={(e) => setCreateTitle(e.target.value)}
          onPressEnter={createProject}
          style={{ marginTop: 8 }}
        />
        <StylePresetPicker value={createStylePreset} onChange={setCreateStylePreset} />
      </Modal>

      <Modal
        className="home-modal"
        title="重命名项目"
        open={renameOpen}
        onOk={submitRename}
        onCancel={() => {
          setRenameOpen(false);
          setRenameTitle('');
        }}
        okText="保存"
        cancelText="取消"
        confirmLoading={renaming}
      >
        <Input
          ref={renameInputRef}
          placeholder="项目名称"
          value={renameTitle}
          onChange={(e) => setRenameTitle(e.target.value)}
          onPressEnter={submitRename}
          maxLength={100}
        />
      </Modal>
    </>
  );
}
