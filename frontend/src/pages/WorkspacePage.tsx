import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Drawer,
  Dropdown,
  Form,
  Input,
  Layout,
  Modal,
  Popconfirm,
  Progress,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  DownOutlined,
  PauseOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
  UpOutlined,
} from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { projectApi, workspaceApi } from '../api/client';
import { setOpenWorkspaceId } from '../lib/openWorkspace';
import {
  STYLE_PRESETS,
  getStyleLabel,
  loadUserSettings,
  normalizeStylePreset,
} from '../lib/userSettings';
import {
  buildExportMarkdown,
  downloadTextFile,
  exportFilename,
  filterExportByShotIds,
} from '../lib/exportProject';
import type { Asset, Project, ProjectSnapshot, ScriptEntry, Shot, VideoRefMode } from '../types';
import { useNavCollapse } from '../components/NavLayout';
import { useAuth } from '../auth/AuthProvider';
import { LoginModal } from '../components/LoginModal';
import { AssistantModule } from '../modules/assistant';
import { AssetReferenceImage } from '../components/workspace/AssetReferenceImage';
import { ProgressButton } from '../components/workspace/ProgressButton';
import { ElementStyleModal } from '../components/workspace/ElementStyleModal';
import { useImageGenQueue } from '../hooks/useImageGenQueue';
import { assetImageJobId } from '../lib/imageGenQueue';
import { ShotRow } from '../components/workspace/ShotRow';
import { WorkspaceHeader } from '../components/workspace/WorkspaceHeader';
import {
  VIDEO_REF_MODE_LABELS,
  VIDEO_REF_MODE_OPTIONS,
  normalizeVideoRefMode,
  resolveVideoRefMode,
} from '../lib/videoRefMode';
import '../styles/home.css';
import '../styles/workspace.css';

const { Content } = Layout;
const { TextArea } = Input;

type WorkspacePanelTab = 'source' | 'assets';

type FailedAction = {
  tip: string;
  retry: () => Promise<void>;
};

const assetTypeLabels: Record<Asset['type'], string> = {
  character: '人物',
  scene: '场景',
  prop: '道具',
};

const assetTypeColors: Record<Asset['type'], string> = {
  character: 'magenta',
  scene: 'green',
  prop: 'orange',
};

interface WorkspaceRow {
  key: string;
  index: number;
  shot?: Shot;
  script?: ScriptEntry;
  linkedAssets: Asset[];
}

export default function WorkspacePage() {
  const { id } = useParams();
  const projectId = Number(id);
  const [project, setProject] = useState<Project | null>(null);
  const [source, setSource] = useState('');
  const [scripts, setScripts] = useState<ScriptEntry[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [shots, setShots] = useState<Shot[]>([]);
  const [loading, setLoading] = useState(false);
  const [progressTip, setProgressTip] = useState('');
  const [progressPercent, setProgressPercent] = useState(0);
  const [failedAction, setFailedAction] = useState<FailedAction | null>(null);
  const batchCancelRef = useRef(false);
  /** 助手流式写入原文时贴底；用户手动编辑则取消 */
  const stickSourceBottomRef = useRef(false);
  const sourceTextAreaRef = useRef<HTMLTextAreaElement>(null);
  const [assistantOpen, setAssistantOpen] = useState(() => loadUserSettings().assistantAutoOpen);
  const [panelTab, setPanelTab] = useState<WorkspacePanelTab | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [generatingShotNumber, setGeneratingShotNumber] = useState<number | null>(null);
  const [dragShotId, setDragShotId] = useState<number | null>(null);
  const [dropTargetShotId, setDropTargetShotId] = useState<number | null>(null);
  const [selectedShotIds, setSelectedShotIds] = useState<number[]>([]);
  const [selectedAssetId, setSelectedAssetId] = useState<number | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [snapshots, setSnapshots] = useState<ProjectSnapshot[]>([]);
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const [assetModalOpen, setAssetModalOpen] = useState(false);
  const {
    enqueue: enqueueImageGen,
    isActive: isImageGenActive,
    isQueued: isImageGenQueued,
    isPending: isImageGenPending,
    queueLength: imageGenQueueLength,
  } = useImageGenQueue();
  const [batchImageBatch, setBatchImageBatch] = useState<{ total: number; done: number } | null>(
    null,
  );
  const [loadingAction, setLoadingAction] = useState<string | null>(null);
  const [batchStylePreset, setBatchStylePreset] = useState<string | undefined>();
  const [elementStyleOpen, setElementStyleOpen] = useState(false);
  const [assetForm] = Form.useForm<{
    type: Asset['type'];
    name: string;
    description?: string;
    imagePrompt?: string;
  }>();
  const { toggleCollapsed } = useNavCollapse();
  const { user } = useAuth();
  const [loginOpen, setLoginOpen] = useState(false);
  const readOnly = project?.readOnly ?? false;
  const isGuest = Boolean(user?.guest);

  const promptLogin = () => {
    message.info('请先登录后再编辑');
    setLoginOpen(true);
  };

  const loadAll = async () => {
    const [p, s, sc, a, sh] = await Promise.all([
      projectApi.get(projectId),
      workspaceApi.getSource(projectId),
      workspaceApi.listScripts(projectId),
      workspaceApi.listAssets(projectId),
      workspaceApi.listShots(projectId),
    ]);
    setProject(p);
    setSource(s.content || '');
    setScripts(sc);
    setAssets(a);
    setShots(sh);
    setSelectedShotIds((prev) => prev.filter((id) => sh.some((shot) => shot.id === id)));
    setSelectedAssetId((prev) => (prev != null && a.some((asset) => asset.id === prev) ? prev : null));
  };

  const refreshShots = async () => {
    const sh = await workspaceApi.listShots(projectId);
    setShots(sh);
  };

  /**
   * 工具栏参考方式：
   * - 有选中：只改已选分镜
   * - 无选中：改作品默认；syncAllShots 时统一全部
   */
  const applyProjectRefMode = async (mode: VideoRefMode, syncAllShots = true) => {
    if (!project || readOnly) return;
    const next = normalizeVideoRefMode(mode);
    const targetIds = selectedShotIds.filter((id) => shots.some((s) => s.id === id));
    try {
      const result = await workspaceApi.applyVideoRefMode(projectId, {
        mode: next,
        shotIds: targetIds.length > 0 ? targetIds : undefined,
        syncAllShots: targetIds.length > 0 ? false : syncAllShots,
      });
      if (targetIds.length === 0) {
        setProject((prev) => (prev ? { ...prev, refPromptMode: result.mode } : prev));
      }
      if (result.shots?.length) {
        setShots(result.shots);
      } else {
        await refreshShots();
      }
      if (targetIds.length > 0) {
        message.success(
          `已将已选 ${result.updatedShotCount || targetIds.length} 个分镜设为「${VIDEO_REF_MODE_LABELS[next]}」`,
        );
      } else if (syncAllShots) {
        message.success(
          `已统一为「${VIDEO_REF_MODE_LABELS[next]}」${
            result.clearedShotOverrides > 0
              ? `（清除 ${result.clearedShotOverrides} 个单镜覆盖）`
              : ''
          }`,
        );
      } else {
        message.success(`默认参考方式已设为「${VIDEO_REF_MODE_LABELS[next]}」`);
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '设置失败');
    }
  };

  const loadSnapshots = async () => {
    setSnapshotLoading(true);
    try {
      setSnapshots(await workspaceApi.listSnapshots(projectId));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载快照失败');
    } finally {
      setSnapshotLoading(false);
    }
  };

  useEffect(() => {
    loadAll().catch((e) => message.error(e.message));
  }, [projectId]);

  useEffect(() => {
    if (!projectId || !user || user.guest) return;
    projectApi.get(projectId).then(setProject).catch(() => undefined);
  }, [projectId, user?.id, user?.guest]);

  useEffect(() => {
    if (projectId) {
      setOpenWorkspaceId(projectId);
    }
  }, [projectId]);

  useEffect(() => {
    if (project?.stylePreset) {
      setBatchStylePreset(normalizeStylePreset(project.stylePreset));
    }
  }, [project?.stylePreset]);

  useEffect(() => {
    if (historyOpen) {
      void loadSnapshots();
    }
  }, [historyOpen, projectId]);

  useEffect(() => {
    if (panelTab !== 'assets' || !projectId) return;
    workspaceApi
      .listAssets(projectId)
      .then(setAssets)
      .catch(() => undefined);
  }, [panelTab, projectId]);

  // 流式写入原文时始终显示最新底部
  useEffect(() => {
    if (!stickSourceBottomRef.current || panelTab !== 'source') return;
    const el = sourceTextAreaRef.current;
    if (!el) return;
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight;
    });
  }, [source, panelTab]);

  const rows: WorkspaceRow[] = useMemo(() => {
    if (shots.length > 0) {
      return [...shots]
        .sort((a, b) => a.orderIndex - b.orderIndex)
        .map((shot, index) => ({
          key: `shot-${shot.id}`,
          index,
          shot,
          script: scripts.find((s) => s.id === shot.scriptId),
          linkedAssets: assets.filter((a) => shot.linkedAssetIds?.includes(a.id)),
        }));
    }
    if (scripts.length > 0) {
      return [...scripts]
        .sort((a, b) => a.orderIndex - b.orderIndex)
        .map((script, index) => ({
          key: `script-${script.id}`,
          index,
          script,
          linkedAssets: [],
        }));
    }
    return [];
  }, [shots, scripts, assets]);

  const filteredRows = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((row) => {
      const parts: string[] = [`分镜 ${row.index + 1}`, `分镜 #${row.index + 1}`];
      if (row.script) {
        parts.push(
          row.script.location,
          row.script.action,
          row.script.dialogue,
          row.script.shot,
          row.script.time,
          ...(row.script.characters || []),
        );
      }
      if (row.shot) {
        parts.push(row.shot.videoPrompt);
        row.shot.referencePrompts?.forEach((ref) => {
          parts.push(ref.prompt, ref.note);
        });
      }
      row.linkedAssets.forEach((asset) => {
        parts.push(asset.name, asset.description);
      });
      return parts.some((part) => part?.toLowerCase().includes(q));
    });
  }, [rows, searchQuery]);

  const jumpTargets = useMemo(() => {
    const q = searchQuery.trim();
    if (!q) return [];
    const exact = q.match(/^分镜\s*#?\s*(\d+)$/i);
    if (exact) {
      const n = Number(exact[1]);
      return rows.filter((row) => row.index + 1 === n).slice(0, 8);
    }
    return filteredRows.slice(0, 8);
  }, [searchQuery, rows, filteredRows]);

  const withLoading = async (fn: () => Promise<void>, tip: string, actionKey?: string) => {
    setLoading(true);
    setProgressTip(tip);
    setLoadingAction(actionKey ?? null);
    setFailedAction(null);
    message.loading({ content: tip, key: 'ai', duration: 0 });
    try {
      await fn();
      message.success({ content: '完成', key: 'ai' });
      setFailedAction(null);
      await loadAll();
    } catch (e) {
      const errText = e instanceof Error ? e.message : '失败';
      message.error({ content: errText, key: 'ai' });
      setFailedAction({
        tip,
        retry: () => withLoading(fn, tip, actionKey),
      });
    } finally {
      setLoading(false);
      setProgressTip('');
      setLoadingAction(null);
    }
  };

  const applyShotStructure = async (fn: () => Promise<Shot[]>, tip: string) => {
    setLoading(true);
    setProgressTip(tip);
    try {
      const next = await fn();
      setShots(next);
      const sc = await workspaceApi.listScripts(projectId);
      setScripts(sc);
      message.success(tip);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
      await loadAll().catch(() => undefined);
    } finally {
      setLoading(false);
      setProgressTip('');
      setDragShotId(null);
      setDropTargetShotId(null);
    }
  };

  const commitShotOrder = async (shotIds: number[]) => {
    try {
      const next = await workspaceApi.reorderShots(projectId, shotIds);
      setShots(next);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '排序失败');
      await loadAll().catch(() => undefined);
    }
  };

  const moveShotBeforeTarget = (fromId: number, toId: number) => {
    if (fromId === toId) return;
    const ordered = [...shots].sort((a, b) => a.orderIndex - b.orderIndex);
    const fromIndex = ordered.findIndex((s) => s.id === fromId);
    const toIndex = ordered.findIndex((s) => s.id === toId);
    if (fromIndex < 0 || toIndex < 0) return;
    const next = [...ordered];
    const [moved] = next.splice(fromIndex, 1);
    next.splice(toIndex, 0, moved);
    const reindexed = next.map((shot, orderIndex) => ({ ...shot, orderIndex }));
    setShots(reindexed);
    void commitShotOrder(reindexed.map((shot) => shot.id));
  };

  const saveSource = async () => {
    await workspaceApi.updateSource(projectId, source);
    message.success('原文已保存');
  };

  const scrollToShot = (row: WorkspaceRow) => {
    const el = document.getElementById(
      row.shot ? `shot-block-${row.shot.id}` : `shot-block-index-${row.index + 1}`,
    );
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const toggleShotSelected = (shotId: number, selected: boolean) => {
    setSelectedShotIds((prev) =>
      selected ? Array.from(new Set([...prev, shotId])) : prev.filter((id) => id !== shotId),
    );
  };

  const selectAllVisible = () => {
    const ids = filteredRows.map((row) => row.shot?.id).filter((id): id is number => id != null);
    setSelectedShotIds(ids);
  };

  const exportSelected = async (format: 'json' | 'markdown') => {
    if (selectedShotIds.length === 0) {
      message.warning('请先勾选要导出的分镜');
      return;
    }
    try {
      const data = filterExportByShotIds(await workspaceApi.export(projectId), selectedShotIds);
      const title = `${data.project?.title || project?.title || 'storyflow'}_选中${selectedShotIds.length}镜`;
      if (format === 'markdown') {
        downloadTextFile(
          exportFilename(title, 'markdown'),
          buildExportMarkdown(data),
          'text/markdown;charset=utf-8',
        );
      } else {
        downloadTextFile(
          exportFilename(title, 'json'),
          `${JSON.stringify(data, null, 2)}\n`,
          'application/json;charset=utf-8',
        );
      }
      message.success(`已导出选中 ${selectedShotIds.length} 个分镜`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    }
  };

  const batchRegenerate = (stylePreset?: string) => {
    const targets = (
      selectedShotIds.length > 0
        ? shots.filter((s) => selectedShotIds.includes(s.id))
        : [...shots]
    ).sort((a, b) => a.orderIndex - b.orderIndex);

    if (targets.length === 0) {
      message.warning('没有可处理的分镜');
      return;
    }

    const run = async () => {
      batchCancelRef.current = false;
      setLoading(true);
      setLoadingAction('batch-regenerate');
      setFailedAction(null);
      setProgressPercent(0);
      const label = stylePreset
        ? `按风格「${stylePreset}」批量改提示词`
        : selectedShotIds.length > 0
          ? `重新生成选中分镜`
          : '重新生成全部分镜提示词';
      try {
        await workspaceApi.prepareShotsBatch(projectId, {
          shotIds: targets.map((s) => s.id),
          stylePreset,
        });
        if (stylePreset) {
          setProject((prev) => (prev ? { ...prev, stylePreset } : prev));
        }

        for (let i = 0; i < targets.length; i++) {
          if (batchCancelRef.current) {
            message.info({ content: `已停止（完成 ${i}/${targets.length}）`, key: 'ai' });
            break;
          }
          const shot = targets[i];
          const tip = `${label}… ${i + 1}/${targets.length}`;
          setProgressTip(tip);
          // 进行中先给进度，避免 1/1 时长期停在 0%
          setProgressPercent(Math.round(((i + 0.2) / targets.length) * 100));
          setGeneratingShotNumber(shot.orderIndex + 1);
          message.loading({ content: tip, key: 'ai', duration: 0 });
          const next = await workspaceApi.regenerateShotPrompts(projectId, shot.id, {
            snapshot: false,
          });
          setShots(next);
          setProgressPercent(Math.round(((i + 1) / targets.length) * 100));
        }

        if (!batchCancelRef.current) {
          setProgressPercent(100);
          message.success({ content: '批量完成', key: 'ai' });
        }
        await loadAll();
      } catch (e) {
        const errText = e instanceof Error ? e.message : '失败';
        message.error({ content: errText, key: 'ai' });
        setFailedAction({ tip: label, retry: async () => { await run(); } });
      } finally {
        setLoading(false);
        setLoadingAction(null);
        setProgressTip('');
        setProgressPercent(0);
        setGeneratingShotNumber(null);
        batchCancelRef.current = false;
      }
    };

    void run();
  };

  const createAsset = async () => {
    const values = await assetForm.validateFields();
    setLoading(true);
    try {
      await workspaceApi.createAsset(projectId, {
        type: values.type,
        name: values.name.trim(),
        description: values.description || '',
        imagePrompt: values.imagePrompt || '',
      });
      message.success('资产已创建');
      setAssetModalOpen(false);
      assetForm.resetFields();
      await loadAll();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setLoading(false);
    }
  };

  const searching = searchQuery.trim().length > 0;
  const panelOpen = panelTab !== null;
  const selectedCount = selectedShotIds.length;
  const batchStyle = batchStylePreset || (project ? normalizeStylePreset(project.stylePreset) : undefined);
  /** 工具栏参考方式展示：有选中看选中项（混合则空），无选中看作品默认 */
  const toolbarRefMode = useMemo((): VideoRefMode | undefined => {
    if (selectedShotIds.length === 0) {
      return normalizeVideoRefMode(project?.refPromptMode);
    }
    const modes = selectedShotIds.map((id) => {
      const shot = shots.find((s) => s.id === id);
      return resolveVideoRefMode(shot?.videoRefMode, project?.refPromptMode);
    });
    const unique = Array.from(new Set(modes));
    return unique.length === 1 ? unique[0] : undefined;
  }, [selectedShotIds, shots, project?.refPromptMode]);

  const togglePanel = (tab: WorkspacePanelTab) => {
    setPanelTab((current) => (current === tab ? null : tab));
  };

  const updateAsset = (assetId: number, patch: Partial<Asset>) => {
    setAssets((prev) => prev.map((asset) => (asset.id === assetId ? { ...asset, ...patch } : asset)));
  };

  const generateAssetImage = (asset: Asset) => {
    if (!asset.imagePrompt?.trim()) {
      message.warning('请先填写或生成生图提示词');
      return;
    }
    const jobId = assetImageJobId(asset.id);
    const status = enqueueImageGen({
      id: jobId,
      label: asset.name,
      run: async () => {
        try {
          const updated = await workspaceApi.generateAssetImage(projectId, asset.id);
          updateAsset(asset.id, updated);
          message.success(`「${asset.name}」参考图已生成`);
        } catch (e) {
          message.error(e instanceof Error ? e.message : '参考图生成失败');
        }
      },
    });
    if (status === 'queued') {
      message.info(`「${asset.name}」已加入生图队列`);
    } else if (status === 'duplicate') {
      message.info(`「${asset.name}」已在队列中`);
    }
  };

  const generateAllAssetImages = () => {
    const targets = assets.filter((asset) => asset.imagePrompt?.trim());
    if (targets.length === 0) {
      message.warning('没有可生成参考图的资产');
      return;
    }
    const batchIds = new Set<string>();
    let done = 0;
    let success = 0;
    for (const asset of targets) {
      const jobId = assetImageJobId(asset.id);
      const status = enqueueImageGen({
        id: jobId,
        label: asset.name,
        run: async () => {
          try {
            const updated = await workspaceApi.generateAssetImage(projectId, asset.id);
            updateAsset(asset.id, updated);
            success += 1;
          } catch (e) {
            message.error(
              `「${asset.name}」参考图生成失败：${e instanceof Error ? e.message : '未知错误'}`,
            );
          } finally {
            if (!batchIds.has(jobId)) return;
            done += 1;
            const total = batchIds.size;
            setBatchImageBatch({ total, done });
            if (done >= total) {
              setBatchImageBatch(null);
              if (success > 0) {
                message.success(`已生成 ${success}/${total} 张参考图`);
              }
            }
          }
        },
      });
      if (status !== 'duplicate') {
        batchIds.add(jobId);
      }
    }
    if (batchIds.size === 0) {
      message.info('所选资产均已在生图队列中');
      return;
    }
    setBatchImageBatch({ total: batchIds.size, done: 0 });
    message.info(`已加入生图队列 ${batchIds.size} 张，将依次生成`);
  };

  return (
    <Layout style={{ height: '100%', overflow: 'hidden', background: 'var(--sf-bg)' }}>
      <Layout.Header
        style={{
          background: 'var(--sf-sidebar)',
          borderBottom: '1px solid var(--sf-border)',
          padding: '0 20px',
          height: 56,
          lineHeight: '56px',
          flexShrink: 0,
        }}
      >
        <WorkspaceHeader
          project={project}
          readOnly={readOnly}
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          assistantOpen={assistantOpen}
          onAssistantToggle={() => {
            if (readOnly) {
              if (isGuest) promptLogin();
              return;
            }
            setAssistantOpen((v) => !v);
          }}
          onNavToggle={toggleCollapsed}
          onStyleChange={async (stylePreset) => {
            if (readOnly) return;
            await projectApi.update(projectId, { stylePreset });
            await loadAll();
          }}
          onProjectRenamed={(updated) => setProject(updated)}
          createOpen={createOpen}
          onCreateOpenChange={setCreateOpen}
          onOpenHistory={() => setHistoryOpen(true)}
        />
      </Layout.Header>

      {readOnly && (
        <Alert
          type="info"
          showIcon
          message={isGuest ? '当前为只读，登录后可继续编辑' : '当前为只读，无权编辑此项目'}
          action={
            isGuest ? (
              <Button size="small" type="primary" onClick={() => setLoginOpen(true)}>
                登录
              </Button>
            ) : undefined
          }
          style={{ margin: 0, borderRadius: 0, flexShrink: 0 }}
        />
      )}

      <Layout className="workspace-body" style={{ flex: 1, minHeight: 0, minWidth: 0, overflow: 'hidden' }}>
        <Content
          className={`workspace-main${panelOpen ? ' workspace-main--panel-expanded' : ''}`}
        >
          <div
            className={`workspace-top-panel${panelOpen ? ' workspace-top-panel--expanded' : ''}`}
          >
            <div className="workspace-top-panel-bar">
              <div className="workspace-top-panel-tabs">
                <button
                  type="button"
                  className={`workspace-top-panel-tab${panelTab === 'source' ? ' workspace-top-panel-tab--active' : ''}`}
                  onClick={() => togglePanel('source')}
                >
                  小说原文
                </button>
                <button
                  type="button"
                  className={`workspace-top-panel-tab${panelTab === 'assets' ? ' workspace-top-panel-tab--active' : ''}`}
                  onClick={() => togglePanel('assets')}
                >
                  资产库
                  {assets.length > 0 && (
                    <span className="workspace-top-panel-tab-count">{assets.length}</span>
                  )}
                </button>
              </div>
              <button
                type="button"
                className="workspace-top-panel-chevron"
                aria-label={panelOpen ? '收起面板' : '展开面板'}
                onClick={() => setPanelTab((current) => (current ? null : 'source'))}
              >
                {panelOpen ? <UpOutlined /> : <DownOutlined />}
              </button>
            </div>

            {panelTab === 'source' && (
              <div className="workspace-top-panel-body workspace-top-panel-body--expanded">
                <div className="workspace-source-textarea-wrap">
                  <TextArea
                    ref={sourceTextAreaRef}
                    className="workspace-source-textarea"
                    value={source}
                    readOnly={readOnly}
                    onChange={(e) => {
                      stickSourceBottomRef.current = false;
                      setSource(e.target.value);
                    }}
                    onScroll={(e) => {
                      if (!stickSourceBottomRef.current) return;
                      const el = e.currentTarget;
                      const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
                      // 用户上滑离开底部则暂停贴底，避免抢滚动
                      if (dist > 48) {
                        stickSourceBottomRef.current = false;
                      }
                    }}
                  />
                </div>
                <div className="workspace-source-actions">
                  <Space>
                    <Button
                      type="primary"
                      disabled={readOnly}
                      onClick={() => {
                        if (readOnly) {
                          if (isGuest) promptLogin();
                          return;
                        }
                        void saveSource();
                      }}
                    >
                      保存原文
                    </Button>
                    <ProgressButton
                      disabled={readOnly}
                      progressing={loadingAction === 'generate-script'}
                      progressPrefix="生成中"
                      estimateMs={90_000}
                      onClick={() => {
                        if (readOnly) {
                          if (isGuest) promptLogin();
                          return;
                        }
                        void withLoading(
                          async () => {
                            await workspaceApi.generateScriptStream(projectId, {
                              onStatus: (text) => {
                                if (!text) return;
                                setProgressTip(text);
                                message.loading({ content: text, key: 'ai', duration: 0 });
                              },
                              onCleared: () => {
                                setScripts([]);
                                setShots([]);
                              },
                              onScripts: (batch) => {
                                if (batch.length === 0) return;
                                setScripts((prev) => [...prev, ...batch]);
                              },
                              onAssetsCleared: () => {
                                setAssets([]);
                              },
                              onAssets: (batch) => {
                                if (batch.length === 0) return;
                                setAssets((prev) => [...prev, ...batch]);
                              },
                              onDone: (payload) => {
                                setScripts(payload.scripts);
                                setShots(payload.shots);
                                setAssets(payload.assets);
                              },
                            });
                          },
                          '生成剧本...',
                          'generate-script',
                        );
                      }}
                    >
                      生成剧本
                    </ProgressButton>
                  </Space>
                </div>
              </div>
            )}

            {panelTab === 'assets' && (
              <div className="workspace-top-panel-body workspace-top-panel-body--expanded">
                <div className="workspace-assets-toolbar">
                  <Typography.Text type="secondary">
                    项目内全部人物、场景与道具
                  </Typography.Text>
                  <Space>
                    <Button
                      icon={<PlusOutlined />}
                      onClick={() => {
                        assetForm.setFieldsValue({
                          type: 'character',
                          name: '',
                          description: '',
                          imagePrompt: '',
                        });
                        setAssetModalOpen(true);
                      }}
                    >
                      新增资产
                    </Button>
                    <ProgressButton
                      progressing={loadingAction === 'extract-assets'}
                      progressPrefix="提取中"
                      progress={
                        loadingAction === 'extract-assets' ? progressPercent : undefined
                      }
                      estimateMs={90_000}
                      onClick={() => {
                        if (readOnly) {
                          if (isGuest) promptLogin();
                          return;
                        }
                        void withLoading(
                          async () => {
                            setProgressPercent(5);
                            await workspaceApi.extractAssetsStream(projectId, {
                              onStatus: (text) => {
                                if (!text) return;
                                setProgressTip(text);
                                message.loading({ content: text, key: 'ai', duration: 0 });
                              },
                              onCleared: () => {
                                setAssets([]);
                                setProgressPercent(10);
                              },
                              onAssets: (batch, _type, stepIndex, stepTotal) => {
                                if (batch.length > 0) {
                                  setAssets((prev) => [...prev, ...batch]);
                                }
                                const total = Math.max(1, stepTotal);
                                setProgressPercent(
                                  Math.min(95, Math.round((stepIndex / total) * 90) + 10),
                                );
                              },
                              onDone: (next) => {
                                setAssets(next);
                                setProgressPercent(100);
                              },
                            });
                          },
                          '提取资产...',
                          'extract-assets',
                        ).finally(() => setProgressPercent(0));
                      }}
                    >
                      提取资产
                    </ProgressButton>
                    <ProgressButton
                      disabled={readOnly || assets.length === 0}
                      progressing={batchImageBatch != null}
                      progress={
                        batchImageBatch
                          ? Math.min(
                              99,
                              Math.round((batchImageBatch.done / batchImageBatch.total) * 100),
                            )
                          : undefined
                      }
                      progressPrefix={
                        imageGenQueueLength > 1
                          ? `队列 ${imageGenQueueLength}`
                          : '批量生成中'
                      }
                      onClick={() => {
                        if (readOnly) {
                          if (isGuest) promptLogin();
                          return;
                        }
                        generateAllAssetImages();
                      }}
                    >
                      批量生成资产图
                    </ProgressButton>
                  </Space>
                </div>
                {assets.length > 0 ? (
                  <div className="workspace-assets-sections">
                    {(['character', 'scene', 'prop'] as const).map((type) => {
                      const group = assets.filter((asset) => asset.type === type);
                      if (group.length === 0) return null;
                      return (
                        <section key={type} className="workspace-asset-section">
                          <div className="workspace-asset-section-title">
                            <Tag color={assetTypeColors[type]}>{assetTypeLabels[type]}</Tag>
                            <span className="workspace-asset-section-count">{group.length}</span>
                          </div>
                          <div className="workspace-assets-grid">
                            {group.map((asset) => (
                              <div
                                key={asset.id}
                                className={
                                  selectedAssetId === asset.id
                                    ? 'workspace-asset-card workspace-asset-card--selected'
                                    : 'workspace-asset-card'
                                }
                                onClick={() =>
                                  setSelectedAssetId((prev) => (prev === asset.id ? null : asset.id))
                                }
                              >
                                <div className="workspace-asset-card-head">
                                  <Input
                                    className="workspace-asset-name-input"
                                    variant="borderless"
                                    value={asset.name}
                                    onChange={(e) => updateAsset(asset.id, { name: e.target.value })}
                                    onBlur={async () => {
                                      const current = assets.find((item) => item.id === asset.id);
                                      if (current) {
                                        await workspaceApi.updateAsset(projectId, asset.id, {
                                          name: current.name,
                                        });
                                      }
                                    }}
                                  />
                                  <Popconfirm
                                    title="删除此资产？"
                                    okText="删除"
                                    cancelText="取消"
                                    onConfirm={() =>
                                      withLoading(
                                        () =>
                                          workspaceApi
                                            .deleteAsset(projectId, asset.id)
                                            .then(() => undefined),
                                        '删除资产...',
                                      )
                                    }
                                  >
                                    <Button size="small" type="text" danger>
                                      删除
                                    </Button>
                                  </Popconfirm>
                                </div>
                                <div className="workspace-asset-card-preview-row">
                                  <AssetReferenceImage
                                    projectId={projectId}
                                    asset={asset}
                                    loading={isImageGenPending(assetImageJobId(asset.id))}
                                    compact
                                  />
                                  <Typography.Paragraph
                                    type="secondary"
                                    className="workspace-asset-card-desc"
                                    editable={{
                                      onChange: async (description) => {
                                        updateAsset(asset.id, { description });
                                        await workspaceApi.updateAsset(projectId, asset.id, {
                                          description,
                                        });
                                      },
                                    }}
                                  >
                                    {asset.description || '点击添加介绍'}
                                  </Typography.Paragraph>
                                </div>
                                <div className="workspace-field-label">生图提示词</div>
                                <TextArea
                                  rows={3}
                                  value={asset.imagePrompt}
                                  onChange={(e) =>
                                    updateAsset(asset.id, { imagePrompt: e.target.value })
                                  }
                                  onBlur={async () => {
                                    const current = assets.find((item) => item.id === asset.id);
                                    if (current) {
                                      await workspaceApi.updateAsset(projectId, asset.id, {
                                        imagePrompt: current.imagePrompt,
                                      });
                                    }
                                  }}
                                />
                                <div className="workspace-asset-card-foot">
                                  <Select
                                    size="small"
                                    variant="borderless"
                                    className="workspace-asset-move-select"
                                    value={asset.type}
                                    options={[
                                      { value: 'character', label: '移至人物' },
                                      { value: 'scene', label: '移至场景' },
                                      { value: 'prop', label: '移至道具' },
                                    ]}
                                    onChange={async (nextType) => {
                                      updateAsset(asset.id, { type: nextType });
                                      await workspaceApi.updateAsset(projectId, asset.id, {
                                        type: nextType,
                                      });
                                    }}
                                  />
                                  <ProgressButton
                                    size="small"
                                    progressing={isImageGenActive(assetImageJobId(asset.id))}
                                    queued={isImageGenQueued(assetImageJobId(asset.id))}
                                    progressPrefix="生成中"
                                    estimateMs={20_000}
                                    disabled={readOnly || !asset.imagePrompt?.trim()}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      if (readOnly) {
                                        if (isGuest) promptLogin();
                                        return;
                                      }
                                      generateAssetImage(asset);
                                    }}
                                  >
                                    生成资产图
                                  </ProgressButton>
                                  <ProgressButton
                                    size="small"
                                    progressing={loadingAction === `asset-prompt-${asset.id}`}
                                    progressPrefix="生成中"
                                    estimateMs={45_000}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      withLoading(
                                        async () => {
                                          await workspaceApi.regenerateAssetPrompt(
                                            projectId,
                                            asset.id,
                                          );
                                        },
                                        '重新生成资产提示词...',
                                        `asset-prompt-${asset.id}`,
                                      );
                                    }}
                                  >
                                    重新生成
                                  </ProgressButton>
                                </div>
                              </div>
                            ))}
                          </div>
                        </section>
                      );
                    })}
                  </div>
                ) : (
                  <div className="workspace-assets-empty">
                    <Typography.Text type="secondary">
                      暂无资产，可手动新增或点击「提取资产」
                    </Typography.Text>
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="workspace-scroll">
            {(loading || failedAction) && loadingAction === 'batch-regenerate' && (
              <div className="workspace-progress-bar">
                {loading ? (
                  <>
                    <Progress
                      percent={progressPercent}
                      showInfo
                      status="active"
                      size="small"
                    />
                    <span>{progressTip || '处理中...'}</span>
                    <Button
                      size="small"
                      danger
                      icon={<PauseOutlined />}
                      onClick={() => {
                        batchCancelRef.current = true;
                        message.info('将在当前分镜完成后停止…');
                      }}
                    >
                      停止
                    </Button>
                  </>
                ) : (
                  failedAction && (
                    <>
                      <span className="workspace-progress-error">上次失败：{failedAction.tip}</span>
                      <Button
                        size="small"
                        icon={<ReloadOutlined />}
                        onClick={() => void failedAction.retry()}
                      >
                        重试
                      </Button>
                    </>
                  )
                )}
              </div>
            )}

            {searching && jumpTargets.length > 0 && (
              <div className="workspace-jump-bar">
                <span className="workspace-jump-bar-label">定位到</span>
                {jumpTargets.map((row) => (
                  <button
                    key={row.key}
                    type="button"
                    className="workspace-jump-chip"
                    onClick={() => scrollToShot(row)}
                  >
                    分镜 #{row.index + 1}
                  </button>
                ))}
              </div>
            )}

            {shots.length > 0 && (
              <div className="workspace-batch-bar">
                <Space wrap size={8}>
                  <Button size="small" onClick={selectAllVisible}>
                    全选当前
                  </Button>
                  <Button size="small" onClick={() => setSelectedShotIds([])} disabled={selectedCount === 0}>
                    清空选择
                  </Button>
                  <Typography.Text type="secondary">已选 {selectedCount} 项</Typography.Text>
                  <ProgressButton
                    size="small"
                    type="primary"
                    disabled={loading && loadingAction !== 'batch-regenerate'}
                    progressing={loadingAction === 'batch-regenerate'}
                    progress={
                      loadingAction === 'batch-regenerate' ? progressPercent : undefined
                    }
                    progressPrefix="重生中"
                    onClick={() => batchRegenerate(batchStyle)}
                  >
                    {selectedCount > 0 ? `重生选中(${selectedCount})` : '全部重生'}
                  </ProgressButton>
                  <div className="workspace-batch-style-group">
                    <Select
                      size="small"
                      className="workspace-batch-style-select"
                      variant="borderless"
                      popupMatchSelectWidth={false}
                      value={batchStyle}
                      placeholder="选择风格"
                      disabled={loading || !batchStyle}
                      options={STYLE_PRESETS.map((preset) => ({
                        value: preset.value,
                        label: preset.label,
                      }))}
                      onChange={(value) => {
                        const next = normalizeStylePreset(value);
                        setBatchStylePreset(next);
                        void projectApi.update(projectId, { stylePreset: next }).then(() => {
                          setProject((prev) => (prev ? { ...prev, stylePreset: next } : prev));
                        });
                      }}
                    />
                    <span className="workspace-batch-style-divider" aria-hidden>
                      |
                    </span>
                    <Popconfirm
                      title="按所选风格批量改提示词？"
                      description={
                        selectedCount > 0
                          ? `将对已选 ${selectedCount} 个分镜应用「${batchStyle ? getStyleLabel(batchStyle) : ''}」并重新生成`
                          : `将对全部分镜应用「${batchStyle ? getStyleLabel(batchStyle) : ''}」并重新生成`
                      }
                      okText="开始"
                      cancelText="取消"
                      disabled={loading || !batchStyle}
                      onConfirm={() => batchStyle && batchRegenerate(batchStyle)}
                    >
                      <Button
                        size="small"
                        type="text"
                        className="workspace-batch-style-action"
                        disabled={loading || !batchStyle}
                      >
                        按风格批量改
                      </Button>
                    </Popconfirm>
                  </div>
                  <div className="workspace-batch-style-group">
                    <Select
                      size="small"
                      className="workspace-batch-ref-select"
                      variant="borderless"
                      popupMatchSelectWidth={false}
                      value={toolbarRefMode}
                      placeholder={selectedCount > 0 ? '混合' : '参考方式'}
                      allowClear={false}
                      disabled={readOnly || !project || loading}
                      options={VIDEO_REF_MODE_OPTIONS.map((opt) => ({
                        value: opt.value,
                        label: opt.label,
                      }))}
                      onChange={(value) => {
                        void applyProjectRefMode(value as VideoRefMode, selectedCount === 0);
                      }}
                    />
                    <span className="workspace-batch-style-divider" aria-hidden>
                      |
                    </span>
                    <Button
                      size="small"
                      type="text"
                      className="workspace-batch-style-action"
                      icon={<SettingOutlined />}
                      disabled={readOnly || !project}
                      onClick={() => setElementStyleOpen(true)}
                      title={
                        selectedCount > 0
                          ? `创作设置（参考方式将应用到已选 ${selectedCount} 项）`
                          : '创作设置（可统一全部分镜）'
                      }
                    >
                      创作设置
                    </Button>
                  </div>
                  <Dropdown
                    menu={{
                      items: [
                        { key: 'md', label: '导出 Markdown', onClick: () => void exportSelected('markdown') },
                        { key: 'json', label: '导出 JSON', onClick: () => void exportSelected('json') },
                      ],
                    }}
                  >
                    <Button size="small" disabled={selectedCount === 0}>
                      导出选中 <DownOutlined />
                    </Button>
                  </Dropdown>
                </Space>
              </div>
            )}

            <Spin spinning={loading && loadingAction == null}>
              {rows.length === 0 ? (
                <div className="workspace-empty card-block">
                  <Typography.Title level={5} style={{ margin: 0, color: 'var(--sf-text-muted)' }}>
                    暂无分镜内容
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    展开上方「小说原文」，粘贴内容后点击「一键生成」或「生成剧本」
                  </Typography.Text>
                </div>
              ) : filteredRows.length === 0 ? (
                <div className="workspace-empty card-block">
                  <Typography.Title level={5} style={{ margin: 0, color: 'var(--sf-text-muted)' }}>
                    未找到匹配的分镜
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    试试搜索人物、地点或台词关键词，或输入「分镜 3」定位
                  </Typography.Text>
                </div>
              ) : (
                <div className="workspace-shot-list">
                  {filteredRows.map((row, rowIndex) => (
                    <ShotRow
                      key={row.key}
                      projectId={projectId}
                      index={row.index}
                      shot={row.shot}
                      script={row.script}
                      linkedAssets={row.linkedAssets}
                      projectRefPromptMode={project?.refPromptMode}
                      canMergeNext={Boolean(row.shot && filteredRows[rowIndex + 1]?.shot)}
                      dragDisabled={searching || loading}
                      isDragging={row.shot != null && dragShotId === row.shot.id}
                      isDropTarget={row.shot != null && dropTargetShotId === row.shot.id}
                      selected={row.shot != null && selectedShotIds.includes(row.shot.id)}
                      onSelectedChange={(checked) => {
                        if (row.shot) toggleShotSelected(row.shot.id, checked);
                      }}
                      onScriptChange={(script) =>
                        setScripts((prev) => prev.map((s) => (s.id === script.id ? script : s)))
                      }
                      onShotChange={(shot) =>
                        setShots((prev) => prev.map((s) => (s.id === shot.id ? shot : s)))
                      }
                      onReload={loadAll}
                      regeneratingPrompt={
                        row.shot != null && loadingAction === `shot-prompt-${row.shot.id}`
                      }
                      onRegenerateShot={() =>
                        withLoading(
                          async () => {
                            if (row.shot) {
                              await workspaceApi.regenerateShotPrompts(projectId, row.shot.id);
                            }
                          },
                          '重新生成分镜提示词...',
                          row.shot ? `shot-prompt-${row.shot.id}` : undefined,
                        )
                      }
                      onInsertAfter={() => {
                        if (!row.shot) return;
                        void applyShotStructure(
                          () => workspaceApi.insertShot(projectId, row.shot!.id),
                          '已插入空分镜',
                        );
                      }}
                      onMergeNext={() => {
                        const next = filteredRows[rowIndex + 1]?.shot;
                        if (!row.shot || !next) return;
                        void applyShotStructure(
                          () => workspaceApi.mergeShots(projectId, row.shot!.id, next.id),
                          '已合并分镜',
                        );
                      }}
                      onSplit={() => {
                        if (!row.shot) return;
                        void applyShotStructure(
                          () => workspaceApi.splitShot(projectId, row.shot!.id),
                          '已拆分分镜',
                        );
                      }}
                      onDelete={() => {
                        if (!row.shot) return;
                        void applyShotStructure(
                          () => workspaceApi.deleteShot(projectId, row.shot!.id),
                          '已删除分镜',
                        );
                      }}
                      onDragStart={(shotId) => {
                        setDragShotId(shotId);
                        setDropTargetShotId(null);
                      }}
                      onDragOver={(shotId) => {
                        if (dragShotId != null && dragShotId !== shotId) {
                          setDropTargetShotId(shotId);
                        }
                      }}
                      onDrop={(targetShotId) => {
                        if (dragShotId == null || dragShotId === targetShotId) {
                          setDragShotId(null);
                          setDropTargetShotId(null);
                          return;
                        }
                        const fromId = dragShotId;
                        setDragShotId(null);
                        setDropTargetShotId(null);
                        moveShotBeforeTarget(fromId, targetShotId);
                      }}
                      onDragEnd={() => {
                        setDragShotId(null);
                        setDropTargetShotId(null);
                      }}
                      generating={generatingShotNumber === row.index + 1}
                      readOnly={readOnly}
                    />
                  ))}
                </div>
              )}
            </Spin>
          </div>
        </Content>

        {!readOnly && (
        <AssistantModule
          projectId={projectId}
          open={assistantOpen}
          onOpenChange={setAssistantOpen}
          focus={{
            focus:
              selectedAssetId != null
                ? 'asset'
                : selectedShotIds.length === 1
                  ? 'shot'
                  : 'global',
            focusId:
              selectedAssetId != null
                ? selectedAssetId
                : selectedShotIds.length === 1
                  ? selectedShotIds[0]
                  : undefined,
            selectedShotIds: selectedShotIds.length > 0 ? selectedShotIds : undefined,
          }}
          onSourceStreaming={(content) => {
            stickSourceBottomRef.current = true;
            setPanelTab('source');
            setSource(content);
          }}
          onShotsStreaming={(event) => {
            if (event.phase === 'streaming' && event.shotNumber != null && event.videoPrompt != null) {
              setShots((prev) =>
                prev.map((s) =>
                  s.orderIndex + 1 === event.shotNumber
                    ? { ...s, videoPrompt: event.videoPrompt! }
                    : s,
                ),
              );
              return;
            }
            refreshShots().catch(() => undefined);
          }}
          onScriptsStreaming={(event) => {
            if (event.phase === 'cleared') {
              setScripts([]);
              setShots([]);
              return;
            }
            if (event.phase === 'append' && event.items && event.items.length > 0) {
              setScripts((prev) => [...prev, ...event.items!]);
            }
          }}
          onAssistantStatus={(text) => {
            if (!text) {
              setGeneratingShotNumber(null);
              return;
            }
            const match = text.match(/正在生成分镜 #(\d+)/);
            if (match) {
              setGeneratingShotNumber(Number(match[1]));
            }
          }}
          onProjectChanged={() => loadAll().catch(() => undefined)}
        />
        )}
      </Layout>

      <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />

      <Drawer
        title="版本快照"
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        width={420}
      >
        <Space style={{ marginBottom: 16 }}>
          <Button
            type="primary"
            onClick={() =>
              withLoading(async () => {
                await workspaceApi.createSnapshot(projectId, '手动快照');
                await loadSnapshots();
              }, '保存快照...')
            }
          >
            保存当前快照
          </Button>
          <Button onClick={() => void loadSnapshots()} loading={snapshotLoading}>
            刷新
          </Button>
        </Space>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          AI 生成/重新生成前会自动留档。回滚会恢复剧本、资产与分镜提示词。
        </Typography.Paragraph>
        <Spin spinning={snapshotLoading}>
          {snapshots.length === 0 ? (
            <Typography.Text type="secondary">暂无快照</Typography.Text>
          ) : (
            <div className="workspace-snapshot-list">
              {snapshots.map((snap) => (
                <div key={snap.id} className="workspace-snapshot-item">
                  <div>
                    <div className="workspace-snapshot-label">{snap.label}</div>
                    <div className="workspace-snapshot-meta">
                      {snap.kind} · {snap.createdAt?.replace('T', ' ').slice(0, 19)}
                    </div>
                  </div>
                  <Popconfirm
                    title="回滚到此快照？"
                    description="当前状态会先自动再存一份"
                    okText="回滚"
                    cancelText="取消"
                    onConfirm={() =>
                      withLoading(async () => {
                        await workspaceApi.restoreSnapshot(projectId, snap.id);
                        await loadSnapshots();
                      }, '回滚快照...')
                    }
                  >
                    <Button size="small">回滚</Button>
                  </Popconfirm>
                </div>
              ))}
            </div>
          )}
        </Spin>
      </Drawer>

      <Modal
        title="新增资产"
        open={assetModalOpen}
        onOk={() => void createAsset()}
        onCancel={() => setAssetModalOpen(false)}
        okText="创建"
        cancelText="取消"
        confirmLoading={loading}
      >
        <Form form={assetForm} layout="vertical" initialValues={{ type: 'character' }}>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'character', label: '人物' },
                { value: 'scene', label: '场景' },
                { value: 'prop', label: '道具' },
              ]}
            />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：林浅" />
          </Form.Item>
          <Form.Item name="description" label="介绍">
            <TextArea rows={3} placeholder="可选" />
          </Form.Item>
          <Form.Item name="imagePrompt" label="生图提示词">
            <TextArea rows={3} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      <ElementStyleModal
        open={elementStyleOpen}
        project={project}
        selectedShotIds={selectedShotIds}
        onClose={() => setElementStyleOpen(false)}
        onSaved={(next) => setProject(next)}
        onShotsSynced={async () => {
          await refreshShots();
        }}
      />
    </Layout>
  );
}
