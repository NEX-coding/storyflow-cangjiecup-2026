import { CheckOutlined } from '@ant-design/icons';
import { STYLE_PRESETS } from '../lib/userSettings';

interface StylePresetPickerProps {
  value: string;
  onChange: (value: string) => void;
}

export function StylePresetPicker({ value, onChange }: StylePresetPickerProps) {
  return (
    <div className="style-preset-picker">
      <span className="style-preset-picker-label">画面风格</span>
      <div className="style-preset-grid">
        {STYLE_PRESETS.map((preset) => {
          const selected = value === preset.value;
          return (
            <button
              key={preset.value}
              type="button"
              className={`style-preset-card ${selected ? 'selected' : ''}`}
              onClick={() => onChange(preset.value)}
            >
              {selected && (
                <span className="style-preset-check" aria-hidden>
                  <CheckOutlined />
                </span>
              )}
              <span className="style-preset-title">{preset.label}</span>
              <span className="style-preset-desc">{preset.description}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
