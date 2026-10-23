import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AppstoreOutlined,
  ArrowRightOutlined,
  CloseOutlined,
  DeleteOutlined,
  ExperimentOutlined,
  FilterOutlined,
  FolderAddOutlined,
  FolderOutlined,
  MoreOutlined,
  PlusOutlined,
  SearchOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Dropdown, Input, Modal, message } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import { useNavCreate } from '../components/NavLayout';
import { projectApi } from '../api/client';
import { StylePresetPicker } from '../components/StylePresetPicker';
import { STYLE_PRESETS, getStyleLabel, loadUserSettings, normalizeStylePreset } from '../lib/userSettings';
import type { Project } from '../types';
import '../styles/home.css';

type ViewMode = 'grid' | 'list';
type SortKey = 'updatedAt' | 'createdAt' | 'title' | 'shotCount' | 'scriptCount';
type SortOrder = 'asc' | 'desc';

const VIEW_MODE_KEY = 'storyflow-project-view-mode';

function loadViewMode(): ViewMode {
  try {
    const raw = localStorage.getItem(VIEW_MODE_KEY);
    return raw === 'list' ? 'list' : 'grid';
  } catch {
    return 'grid';
  }
}

function formatDate(iso: string) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function ProjectListPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [stylePreset, setStylePreset] = useState(() => loadUserSettings().defaultStylePreset);
  const [search, setSearch] = useState('');
  const [viewMode, setViewMode] = useState<ViewMode>(loadViewMode);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [searchOpen, setSearchOpen] = useState(false);
  const [sortKey, setSortKey] = useState<SortKey>('updatedAt');
  const [sortOrder, setSortOrder] = useState<SortOrder>('desc');
  const [styleFilter, setStyleFilter] = useState<string>('all');
  const [filterOpen, setFilterOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<Project | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  const [renaming, setRenaming] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  const openCreateModal = () => {
    setStylePreset(loadUserSettings().defaultStylePreset);
    setOpen(true);
  };

  useNavCreate(openCreateModal);

  useEffect(() => {
    if ((location.state as { openCreate?: boolean } | null)?.openCreate) {
      openCreateModal();
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.state, location.pathname, navigate]);

  const hasActiveFilter = styleFilter !== 'all' || sortKey !== 'updatedAt' || sortOrder !== 'desc';
  const isSearching = search.trim().length > 0;

  const load = async () => {
    setLoading(true);
    try {
      setProjects(await projectApi.list());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    let list = q ? projects.filter((p) => p.title.toLowerCase().includes(q)) : projects;

    if (styleFilter !== 'all') {
      list = list.filter((p) => normalizeStylePreset(p.stylePreset) === styleFilter);
    }

    return [...list].sort((a, b) => {
      let cmp = 0;
      switch (sortKey) {
        case 'title':
          cmp = a.title.localeCompare(b.title, 'zh-CN');
          break;
        case 'createdAt':
          cmp = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
          break;
        case 'shotCount':
          cmp = a.shotCount - b.shotCount;
          break;
        case 'scriptCount':
          cmp = a.scriptCount - b.scriptCount;
          break;
        default:
          cmp = new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime();
      }
      return sortOrder === 'desc' ? -cmp : cmp;
    });
  }, [projects, search, sortKey, sortOrder, styleFilter]);

  const changeViewMode = (mode: ViewMode) => {
    setViewMode(mode);
    localStorage.setItem(VIEW_MODE_KEY, mode);
  };

  const clearSearch = () => {
    setSearch('');
    setSearchOpen(false);
  };

  const handleSearchKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      clearSearch();
      searchRef.current?.blur();
    }
  };

  const resetFilters = () => {
    setSortKey('updatedAt');
    setSortOrder('desc');
    setStyleFilter('all');
  };

  const openProject = (projectId: number, e?: React.MouseEvent) => {
    if (e?.metaKey || e?.ctrlKey) {
      toggleSelect(projectId, e);
      return;
    }
    navigate(`/projects/${projectId}`);
  };

  const openRenameModal = (project: Project) => {
    setRenameTarget(project);
    setRenameTitle(project.title);
  };

  const submitRename = async () => {
    if (!renameTarget || !renameTitle.trim()) return;
    setRenaming(true);
    try {
      await projectApi.update(renameTarget.id, { title: renameTitle.trim() });
      message.success('已重命名');
      setRenameTarget(null);
      setRenameTitle('');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重命名失败');
    } finally {
      setRenaming(false);
    }
  };

  const getProjectMenuItems = (project: Project) => [
    {
      key: 'open',
      label: '打开',
      onClick: () => navigate(`/projects/${project.id}`),
    },
    {
      key: 'rename',
      label: '重命名',
      onClick: () => openRenameModal(project),
    },
    {
      key: 'select',
      label: selectedIds.has(project.id) ? '取消选中' : '选中',
      onClick: () => toggleSelect(project.id),
    },
    {
      key: 'delete',
      label: '删除',
      danger: true,
      onClick: () => {
        Modal.confirm({
          title: '删除项目？',
          content: project.title,
          okText: '删除',
          okType: 'danger',
          cancelText: '取消',
          onOk: async () => {
            await projectApi.remove(project.id);
            message.success('已删除');
            await load();
          },
        });
      },
    },
  ];

  const createProject = async () => {
    if (!title.trim()) return;
    try {
      const project = await projectApi.create(title.trim(), stylePreset);
      message.success('项目已创建');
      setOpen(false);
      setTitle('');
      navigate(`/projects/${project.id}`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    }
  };

  const createDemoProject = async () => {
    setLoading(true);
    try {
      const project = await projectApi.createDemo();
      message.success('示例项目已创建');
      navigate(`/projects/${project.id}`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setLoading(false);
    }
  };

  const toggleSelect = (id: number, e?: React.MouseEvent) => {
    e?.stopPropagation();
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const deleteSelected = async () => {
    if (selectedIds.size === 0) {
      message.info('请先选中要删除的作品（Ctrl/Cmd + 点击 或右键菜单）');
      return;
    }
    Modal.confirm({
      title: `删除 ${selectedIds.size} 个项目？`,
      content: '此操作不可恢复',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await Promise.all([...selectedIds].map((id) => projectApi.remove(id)));
          message.success('已删除');
          setSelectedIds(new Set());
          await load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败');
        }
      },
    });
  };

  const selectionLabel =
    selectedIds.size > 0
      ? `已选中 ${selectedIds.size} 个作品`
      : isSearching || hasActiveFilter
        ? `共 ${filtered.length} 个作品`
        : '未选中作品';

  const filterPanel = (
    <div className="toolbar-filter-panel">
      <div className="toolbar-filter-section">
        <span className="toolbar-filter-label">排序方式</span>
        <div className="toolbar-filter-chips">
          {(
            [
              ['updatedAt', '最近更新'],
              ['createdAt', '创建时间'],
              ['title', '名称'],
              ['shotCount', '分镜数'],
              ['scriptCount', '剧本数'],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              type="button"
              className={`toolbar-filter-chip ${sortKey === key ? 'active' : ''}`}
              onClick={() => setSortKey(key)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      <div className="toolbar-filter-section">
        <span className="toolbar-filter-label">排序方向</span>
        <div className="toolbar-filter-chips">
          <button
            type="button"
            className={`toolbar-filter-chip ${sortOrder === 'desc' ? 'active' : ''}`}
            onClick={() => setSortOrder('desc')}
          >
            降序
          </button>
          <button
            type="button"
            className={`toolbar-filter-chip ${sortOrder === 'asc' ? 'active' : ''}`}
            onClick={() => setSortOrder('asc')}
          >
            升序
          </button>
        </div>
      </div>
      <div className="toolbar-filter-section">
        <span className="toolbar-filter-label">风格筛选</span>
        <div className="toolbar-filter-chips">
          <button
            type="button"
            className={`toolbar-filter-chip ${styleFilter === 'all' ? 'active' : ''}`}
            onClick={() => setStyleFilter('all')}
          >
            全部
          </button>
          {STYLE_PRESETS.map(({ value, label }) => (
            <button
              key={value}
              type="button"
              className={`toolbar-filter-chip ${styleFilter === value ? 'active' : ''}`}
              onClick={() => setStyleFilter(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      {hasActiveFilter && (
        <button type="button" className="toolbar-filter-reset" onClick={resetFilters}>
          重置筛选
        </button>
      )}
    </div>
  );

  return (
    <AppLayout>
      <section className="home-hero">
        <div className="home-hero-glow" />
        <div className="home-hero-content">
          <h1 className="home-hero-title">
            StoryFlow · <span className="accent">短剧提示词工作台</span>
          </h1>
          <p className="home-hero-desc">
            从小说原文到结构化剧本、资产库、分镜提示词与参考图，一站式 AI 辅助案头创作
          </p>
          <div className="home-hero-features">
            <span className="home-hero-pill">AI 剧本生成</span>
            <span className="home-hero-pill">分镜提示词</span>
            <span className="home-hero-pill">资产提取</span>
            <span className="home-hero-pill">参考图预览</span>
          </div>
          <button
            type="button"
            className="home-hero-cta"
            disabled={loading}
            onClick={openCreateModal}
          >
            开始创作
            <ArrowRightOutlined />
          </button>
        </div>
      </section>

      <section className="home-projects">
        <div className="home-projects-header">
          <div className="home-projects-title-wrap">
            <h2 className="home-projects-title">作品</h2>
            <span className="home-projects-sub">{selectionLabel}</span>
          </div>
          <div className="home-projects-toolbar">
            {searchOpen || isSearching ? (
              <div className="toolbar-search-wrap">
                <input
                  ref={searchRef}
                  className="toolbar-search"
                  placeholder="搜索作品..."
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={handleSearchKeyDown}
                  autoFocus={searchOpen}
                  onBlur={() => !search.trim() && setSearchOpen(false)}
                />
                {isSearching && (
                  <button
                    type="button"
                    className="toolbar-search-clear"
                    title="清除搜索"
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={clearSearch}
                  >
                    <CloseOutlined />
                  </button>
                )}
              </div>
            ) : (
              <button
                type="button"
                className="toolbar-btn"
                title="搜索"
                onClick={() => {
                  setSearchOpen(true);
                  requestAnimationFrame(() => searchRef.current?.focus());
                }}
              >
                <SearchOutlined />
              </button>
            )}
            <button
              type="button"
              className={`toolbar-btn ${viewMode === 'grid' ? 'active' : ''}`}
              title="网格视图"
              onClick={() => changeViewMode('grid')}
            >
              <AppstoreOutlined />
            </button>
            <button
              type="button"
              className={`toolbar-btn ${viewMode === 'list' ? 'active' : ''}`}
              title="列表视图"
              onClick={() => changeViewMode('list')}
            >
              <UnorderedListOutlined />
            </button>
            <Dropdown
              open={filterOpen}
              onOpenChange={setFilterOpen}
              trigger={['click']}
              placement="bottomRight"
              popupRender={() => filterPanel}
            >
              <button
                type="button"
                className={`toolbar-btn ${hasActiveFilter ? 'active' : ''}`}
                title="筛选与排序"
              >
                <FilterOutlined />
              </button>
            </Dropdown>
            <button type="button" className="toolbar-btn" title="新建项目" onClick={openCreateModal}>
              <FolderAddOutlined />
            </button>
            <button
              type="button"
              className={`toolbar-btn danger ${selectedIds.size > 0 ? 'has-selection' : ''}`}
              title={selectedIds.size > 0 ? `删除 ${selectedIds.size} 个选中作品` : '删除选中作品'}
              onClick={deleteSelected}
            >
              <DeleteOutlined />
            </button>
          </div>
        </div>

        {viewMode === 'grid' ? (
          <div className="project-grid">
            <div className="project-card-wrap">
              <button type="button" className="project-card project-card-new" onClick={openCreateModal}>
                <span className="project-card-new-icon">
                  <PlusOutlined />
                </span>
                <span>新建项目</span>
              </button>
            </div>

            {loading && filtered.length === 0 ? (
              <div className="project-empty">加载中...</div>
            ) : filtered.length === 0 ? (
              <div className="project-empty">
                {isSearching || hasActiveFilter ? '没有匹配的作品' : '暂无作品，点击「新建项目」开始创作'}
              </div>
            ) : (
              filtered.map((project) => (
                <div key={project.id} className="project-card-wrap">
                  <div
                    role="button"
                    tabIndex={0}
                    className={`project-card ${selectedIds.has(project.id) ? 'selected' : ''}`}
                    onClick={(e) => openProject(project.id, e)}
                    onKeyDown={(e) => e.key === 'Enter' && navigate(`/projects/${project.id}`)}
                    onContextMenu={(e) => {
                      e.preventDefault();
                      toggleSelect(project.id);
                    }}
                  >
                    <div className="project-card-cover">
                      <FolderOutlined className="project-card-cover-icon" />
                    </div>
                  </div>
                  <div className="project-card-meta">
                    <span className="project-card-title">{project.title}</span>
                    <Dropdown
                      menu={{ items: getProjectMenuItems(project) }}
                      trigger={['click']}
                    >
                      <button
                        type="button"
                        className="project-card-menu"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <MoreOutlined />
                      </button>
                    </Dropdown>
                  </div>
                  <div className="project-card-stats">
                    <span>{project.shotCount || 0} 分镜</span>
                    <span className="dot">|</span>
                    <span>{project.scriptCount || 0} 剧本</span>
                    <span className="dot">|</span>
                    <span>{formatDate(project.updatedAt)}</span>
                  </div>
                </div>
              ))
            )}
          </div>
        ) : (
          <div className="project-list-table">
            <div className="project-list-row project-list-head">
              <span>项目名称</span>
              <span>风格</span>
              <span>剧本</span>
              <span>分镜</span>
              <span>更新时间</span>
              <span />
            </div>
            <div
              className="project-list-row project-list-new"
              onClick={openCreateModal}
            >
              <span className="project-list-new-label">
                <PlusOutlined /> 新建项目
              </span>
              <span />
              <span />
              <span />
              <span />
              <span />
            </div>
            {loading && filtered.length === 0 ? (
              <div className="project-list-empty">加载中...</div>
            ) : filtered.length === 0 ? (
              <div className="project-list-empty">
                {isSearching || hasActiveFilter ? '没有匹配的作品' : '暂无作品'}
              </div>
            ) : (
              filtered.map((project) => (
                <div
                  key={project.id}
                  className={`project-list-row ${selectedIds.has(project.id) ? 'selected' : ''}`}
                  onClick={(e) => openProject(project.id, e)}
                  onContextMenu={(e) => {
                    e.preventDefault();
                    toggleSelect(project.id);
                  }}
                >
                  <span className="project-card-title">{project.title}</span>
                  <span style={{ color: 'var(--sf-text-muted)', fontSize: 13 }}>{getStyleLabel(project.stylePreset)}</span>
                  <span style={{ color: 'var(--sf-text-muted)', fontSize: 13 }}>{project.scriptCount}</span>
                  <span style={{ color: 'var(--sf-text-muted)', fontSize: 13 }}>{project.shotCount}</span>
                  <span style={{ color: 'var(--sf-text-muted)', fontSize: 12 }}>{formatDate(project.updatedAt)}</span>
                  <Dropdown
                    menu={{ items: getProjectMenuItems(project) }}
                    trigger={['click']}
                  >
                    <button
                      type="button"
                      className="project-card-menu"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <MoreOutlined />
                    </button>
                  </Dropdown>
                </div>
              ))
            )}
          </div>
        )}
      </section>

      <Modal
        className="home-modal home-modal--wide"
        title="新建项目"
        open={open}
        onOk={createProject}
        onCancel={() => setOpen(false)}
        okText="创建"
        cancelText="取消"
        width={640}
      >
        <div style={{ marginBottom: 12 }}>
          <button
            type="button"
            className="home-hero-cta"
            style={{ padding: '6px 16px', fontSize: 13 }}
            disabled={loading}
            onClick={createDemoProject}
          >
            <ExperimentOutlined /> 快速体验示例项目
          </button>
        </div>
        <Input
          placeholder="项目名称"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          onPressEnter={createProject}
          style={{ marginTop: 8 }}
        />
        <StylePresetPicker value={stylePreset} onChange={setStylePreset} />
      </Modal>

      <Modal
        className="home-modal"
        title="重命名项目"
        open={renameTarget !== null}
        onOk={submitRename}
        onCancel={() => {
          setRenameTarget(null);
          setRenameTitle('');
        }}
        okText="保存"
        cancelText="取消"
        confirmLoading={renaming}
      >
        <Input
          placeholder="项目名称"
          value={renameTitle}
          onChange={(e) => setRenameTitle(e.target.value)}
          onPressEnter={submitRename}
          maxLength={100}
        />
      </Modal>
    </AppLayout>
  );
}
