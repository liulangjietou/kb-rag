import { DownOutlined } from '@ant-design/icons';
import { Dropdown } from 'antd';
import type { ButtonProps, MenuProps } from 'antd';
import { cloneElement, useId, useState } from 'react';
import type { CSSProperties, ReactElement, ReactNode } from 'react';
import { isThemePreference } from '../theme/themePreference';
import { getThemePreset } from '../theme/presets';
import { useThemePreset } from '../theme/ThemePresetContext';

export interface ThemePresetSwitcherProps {
  compact?: boolean;
}

function withButtonProps(button: ReactNode, props: ButtonProps): ReactElement {
  return cloneElement(button as ReactElement<ButtonProps>, props);
}

export default function ThemePresetSwitcher({ compact = false }: ThemePresetSwitcherProps) {
  const { preset, presetId, presets, selectPreset, cyclePreset } = useThemePreset();
  const [open, setOpen] = useState(false);
  const menuId = `theme-preset-menu-${useId()}`;
  const nextPreset = presets[(presets.findIndex((item) => item.id === presetId) + 1) % presets.length];
  const selectedName = presetId === 'system' ? 'System 随行' : preset.name;

  const options = [
    { id: 'system', name: 'System 随行', description: '跟随系统明暗，自动切换清爽与低眩光界面', mode: 'auto', palette: getThemePreset('ocean').palette },
    ...presets,
  ];
  const items: MenuProps['items'] = options.map((item) => ({
    key: item.id,
    label: (
      <span className="theme-preset-option">
        <span
          className="theme-preset-option__swatch"
          style={{ backgroundColor: item.palette.primary } as CSSProperties}
          aria-hidden="true"
        />
        <span className="theme-preset-option__copy">
          <span className="theme-preset-option__name">{item.name}</span>
          <span className="theme-preset-option__description">{item.description}</span>
        </span>
        <span className="theme-preset-option__mode">{item.mode.toUpperCase()}</span>
      </span>
    ),
  }));

  const handleSelect: MenuProps['onClick'] = ({ key }) => {
    if (!isThemePreference(key)) {
      return;
    }
    selectPreset(key);
    setOpen(false);
  };

  const cycleLabel = `当前为 ${selectedName}，切换到 ${nextPreset.name}`;

  return (
    <span className="theme-preset-switcher">
      <Dropdown.Button
        type="default"
        trigger={['click']}
        open={open}
        onOpenChange={setOpen}
        destroyOnHidden
        icon={<DownOutlined aria-hidden="true" />}
        onClick={cyclePreset}
        menu={{
          id: menuId,
          className: 'theme-preset-switcher__menu',
          items,
          selectable: true,
          selectedKeys: [presetId],
          onClick: handleSelect,
        }}
        buttonsRender={(buttons) => [
          withButtonProps(buttons[0], {
            'aria-label': cycleLabel,
            title: cycleLabel,
          }),
          withButtonProps(buttons[1], {
            'aria-label': '选择界面主题',
            'aria-haspopup': 'menu',
            'aria-expanded': open,
            'aria-controls': menuId,
            title: '选择界面主题',
          }),
        ]}
      >
        <span className="theme-preset-switcher__button-content">
          <span
            className="theme-preset-switcher__swatch"
            style={{ backgroundColor: preset.palette.primary } as CSSProperties}
            aria-hidden="true"
          />
          {!compact && <span>{selectedName}</span>}
        </span>
      </Dropdown.Button>
      <span className="theme-preset-switcher__status" role="status" aria-live="polite">
        当前界面主题：{selectedName}{presetId === 'system' ? `，当前${preset.mode === 'dark' ? '深色' : '浅色'}` : ''}
      </span>
    </span>
  );
}
