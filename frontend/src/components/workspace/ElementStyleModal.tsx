import { useEffect, useState } from 'react';
import { Alert, Checkbox, Collapse, Form, Input, Modal, Radio, Typography, message } from 'antd';
import type { AspectRatio, ElementStyles, Project, VideoRefMode } from '../../types';
import { projectApi, workspaceApi } from '../../api/client';
import { VIDEO_REF_MODE_LABELS, normalizeVideoRefMode } from '../../lib/videoRefMode';
import {
  ASPECT_RATIO_LABELS,
  IMAGE_ASPECT_RATIOS,
  VIDEO_ASPECT_RATIOS,
  normalizeImageAspectRatio,
  normalizeVideoAspectRatio,
} from '../../lib/aspectRatio';
import { defaultElementPrefix, defaultElementSuffix } from '../../lib/styleElementDefaults';

const { TextArea } = Input;

export interface ElementStyleModalProps {
  open: boolean;
  project: Project | null;
  /** 当前选中的分镜；有选中时参考方式只应用到这些分镜 */
  selectedShotIds?: number[];
  onClose: () => void;
  onSaved: (project: Project) => void;
  onShotsSynced?: () => void | Promise<void>;
}

/** 前缀默认留空，由后端角色设定板/画风模板主导；勿堆「干净线条」等会与写实冲突的媒介词 */
const DEFAULTS = {
  character: {
    prefix: '',
    suffix: '无文字水印',
  },
  scene: {
    prefix: '',
    suffix: '空间层次清晰，无文字水印',
  },
  prop: {
    prefix: '',
    suffix: '材质清晰，无文字水印',
  },
} satisfies ElementStyles;

const MODE_HINTS: Record<VideoRefMode, string> = {
  first_last:
    '每个分镜产出首帧 + 尾帧两张静帧提示词/参考图，用来锁定短剧镜头的起幅与落幅。',
  single:
    '每个分镜产出一张详尽「故事版」（等大格子；每格含景别、环境、姿态表情、光影），便于预览整镜节奏后再写视频提示词。',
  none: '不生成分镜参考图，只保留视频提示词（纯文本分镜）。',
};

/** 作品级创作设置：默认参考方式 + 元素前缀 */
export function ElementStyleModal({
  open,
  project,
  selectedShotIds = [],
  onClose,
  onSaved,
  onShotsSynced,
}: ElementStyleModalProps) {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [applyRefMode, setApplyRefMode] = useState(true);
  const watchedMode = Form.useWatch('refPromptMode', form) as VideoRefMode | undefined;
  const selectedCount = selectedShotIds.length;
  const scopeSelected = selectedCount > 0;

  useEffect(() => {
    if (!open || !project) return;
    const styles = project.elementStyles || DEFAULTS;
    form.setFieldsValue({
      refPromptMode: normalizeVideoRefMode(project.refPromptMode),
      videoAspectRatio: normalizeVideoAspectRatio(project.videoAspectRatio),
      refImageAspectRatio: normalizeImageAspectRatio(project.refImageAspectRatio),
      assetImageAspectRatio: normalizeImageAspectRatio(project.assetImageAspectRatio),
      styleReferenceNote: project.styleReferenceNote || '',
      characterPrefix: styles.character?.prefix || DEFAULTS.character.prefix,
      characterSuffix: styles.character?.suffix || DEFAULTS.character.suffix,
      scenePrefix: styles.scene?.prefix || DEFAULTS.scene.prefix,
      sceneSuffix: styles.scene?.suffix || DEFAULTS.scene.suffix,
      propPrefix: styles.prop?.prefix || DEFAULTS.prop.prefix,
      propSuffix: styles.prop?.suffix || DEFAULTS.prop.suffix,
    });
    setApplyRefMode(true);
  }, [open, project, form]);

  const handleOk = async () => {
    if (!project) return;
    const values = await form.validateFields();
    const mode = normalizeVideoRefMode(values.refPromptMode);
    const videoAspectRatio = normalizeVideoAspectRatio(values.videoAspectRatio as AspectRatio);
    const refImageAspectRatio = normalizeImageAspectRatio(values.refImageAspectRatio as AspectRatio);
    const assetImageAspectRatio = normalizeImageAspectRatio(values.assetImageAspectRatio as AspectRatio);
    setSaving(true);
    try {
      // 画幅与元素前缀始终是作品级；参考方式仅在无选中时改作品默认
      const updated = await projectApi.update(project.id, {
        elementStyles: {
          character: { prefix: values.characterPrefix, suffix: values.characterSuffix },
          scene: { prefix: values.scenePrefix, suffix: values.sceneSuffix },
          prop: { prefix: values.propPrefix, suffix: values.propSuffix },
        },
        videoAspectRatio,
        refImageAspectRatio,
        assetImageAspectRatio,
        styleReferenceNote: values.styleReferenceNote ?? '',
        ...(scopeSelected ? {} : { refPromptMode: mode }),
      });
      onSaved(updated);

      if (applyRefMode) {
        if (scopeSelected) {
          const result = await workspaceApi.applyVideoRefMode(project.id, {
            mode,
            shotIds: selectedShotIds,
            syncAllShots: false,
          });
          await onShotsSynced?.();
          message.success(
            `已保存画幅；已选 ${result.updatedShotCount || selectedCount} 个分镜设为「${VIDEO_REF_MODE_LABELS[mode]}」`,
          );
        } else {
          await workspaceApi.applyVideoRefMode(project.id, {
            mode,
            syncAllShots: true,
          });
          await onShotsSynced?.();
          message.success(
            `已保存画幅与「${VIDEO_REF_MODE_LABELS[mode]}」，并同步到全部分镜`,
          );
        }
      } else {
        message.success(
          `已保存：视频提示词 ${ASPECT_RATIO_LABELS[videoAspectRatio]} · 参考图 ${ASPECT_RATIO_LABELS[refImageAspectRatio]} · 资产图 ${ASPECT_RATIO_LABELS[assetImageAspectRatio]}`,
        );
      }
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title="创作设置"
      open={open}
      onCancel={onClose}
      onOk={() => void handleOk()}
      confirmLoading={saving}
      width={720}
      destroyOnClose
      okText="保存"
    >
      <Form form={form} layout="vertical" size="small">
        <Typography.Title level={5} style={{ marginTop: 0, marginBottom: 8, fontSize: 14 }}>
          画幅比例（作品级）
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          视频提示词、分镜参考图、资产图可分别设定画幅；只影响之后新生成的内容。
        </Typography.Paragraph>
        <Form.Item name="videoAspectRatio" label="视频提示词画幅" style={{ marginBottom: 8 }}>
          <Radio.Group optionType="button" buttonStyle="solid">
            {VIDEO_ASPECT_RATIOS.map((r) => (
              <Radio.Button key={r} value={r}>
                {ASPECT_RATIO_LABELS[r]}
              </Radio.Button>
            ))}
          </Radio.Group>
        </Form.Item>
        <Form.Item name="refImageAspectRatio" label="分镜参考图比例" style={{ marginBottom: 8 }}>
          <Radio.Group optionType="button" buttonStyle="solid">
            {IMAGE_ASPECT_RATIOS.map((r) => (
              <Radio.Button key={r} value={r}>
                {ASPECT_RATIO_LABELS[r]}
              </Radio.Button>
            ))}
          </Radio.Group>
        </Form.Item>
        <Form.Item name="assetImageAspectRatio" label="资产图比例（人物/场景/道具）" style={{ marginBottom: 16 }}>
          <Radio.Group optionType="button" buttonStyle="solid">
            {IMAGE_ASPECT_RATIOS.map((r) => (
              <Radio.Button key={r} value={r}>
                {ASPECT_RATIO_LABELS[r]}
              </Radio.Button>
            ))}
          </Radio.Group>
        </Form.Item>

        <Typography.Title level={5} style={{ marginTop: 0, marginBottom: 8, fontSize: 14 }}>
          分镜参考方式
        </Typography.Title>
        {scopeSelected ? (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 8 }}
            message={`当前已选 ${selectedCount} 个分镜：参考方式将只应用到这些分镜，不改作品默认。`}
          />
        ) : (
          <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
            未选中分镜时改的是作品默认；勾选下方同步可统一全部分镜。
          </Typography.Paragraph>
        )}
        <Form.Item name="refPromptMode" style={{ marginBottom: 8 }}>
          <Radio.Group optionType="button" buttonStyle="solid">
            <Radio.Button value="first_last">首尾帧</Radio.Button>
            <Radio.Button value="single">单图</Radio.Button>
            <Radio.Button value="none">无参考</Radio.Button>
          </Radio.Group>
        </Form.Item>
        {watchedMode ? (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message={MODE_HINTS[normalizeVideoRefMode(watchedMode)]}
          />
        ) : null}
        <Checkbox
          checked={applyRefMode}
          onChange={(e) => setApplyRefMode(e.target.checked)}
          style={{ marginBottom: 16 }}
        >
          {scopeSelected
            ? `应用到已选 ${selectedCount} 个分镜`
            : '同步到全部分镜（清除单镜覆盖，全部统一为上述方式）'}
        </Checkbox>

        <Form.Item
          name="styleReferenceNote"
          label="风格参考（可选）"
          style={{ marginBottom: 12 }}
          extra="「风格参考」文字版：生资产图 / 分镜参考图时前置，不改库里原文。"
        >
          <TextArea
            rows={2}
            placeholder={
              project?.stylePreset === '真人写实'
                ? '例：电影写真质感，自然光，真实肤质'
                : '例：统一伪厚涂 CG 插画风，细腻光影'
            }
          />
        </Form.Item>

        <Collapse
          ghost
          size="small"
          items={[
            {
              key: 'prefixes',
              label: '元素生图前缀 / 后缀（作品级）',
              children: (
                <>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
                    留空则按当前画风「{project?.stylePreset || '日漫赛璐璐'}」自动补默认词；填了则以你的为准。不改库里原文。
                  </Typography.Paragraph>
                  <Form.Item name="characterPrefix" label="人物 · 前缀">
                    <TextArea
                      rows={2}
                      placeholder={defaultElementPrefix(project?.stylePreset, 'character')}
                    />
                  </Form.Item>
                  <Form.Item name="characterSuffix" label="人物 · 后缀">
                    <Input placeholder={defaultElementSuffix('character')} />
                  </Form.Item>
                  <Form.Item name="scenePrefix" label="场景 · 前缀">
                    <TextArea
                      rows={2}
                      placeholder={defaultElementPrefix(project?.stylePreset, 'scene')}
                    />
                  </Form.Item>
                  <Form.Item name="sceneSuffix" label="场景 · 后缀">
                    <Input placeholder={defaultElementSuffix('scene')} />
                  </Form.Item>
                  <Form.Item name="propPrefix" label="道具 · 前缀">
                    <TextArea
                      rows={2}
                      placeholder={defaultElementPrefix(project?.stylePreset, 'prop')}
                    />
                  </Form.Item>
                  <Form.Item name="propSuffix" label="道具 · 后缀">
                    <Input placeholder={defaultElementSuffix('prop')} />
                  </Form.Item>
                </>
              ),
            },
          ]}
        />
      </Form>
    </Modal>
  );
}
