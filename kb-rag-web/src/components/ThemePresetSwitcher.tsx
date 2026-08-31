import { DownOutlined } from '@ant-design/icons';
import { Dropdown } from 'antd';
import type { ButtonProps, MenuProps } from 'antd';
import { cloneElement, useId, useState } from 'react';
import type { CSSProperties, ReactElement, ReactNode } from 'react';
import { isThemePresetId } from '../theme/presets';
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

  const items: MenuProps['items'] = presets.map((item) => ({
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
      </span>
    ),
  }));

  const handleSelect: MenuProps['onClick'] = ({ key }) => {
    if (!isThemePresetId(key)) {
      return;
    }
    selectPreset(key);
    setOpen(false);
  };

  const cycleLabel = `当前为 ${preset.name}，切换到 ${nextPreset.name}`;

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
          {!compact && <span>{preset.name}</span>}
        </span>
      </Dropdown.Button>
      <span className="theme-preset-switcher__status" role="status" aria-live="polite">
        当前界面主题：{preset.name}
      </span>
    </span>
  );
}
