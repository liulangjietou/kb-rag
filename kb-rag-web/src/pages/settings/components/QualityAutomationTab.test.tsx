// @vitest-environment jsdom
import { App as AntApp } from 'antd';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import QualityAutomationTab from './QualityAutomationTab';

const mocks = vi.hoisted(() => ({
  getQualityAutomationConfig: vi.fn(),
  updateQualityAutomationConfig: vi.fn(),
}));

vi.mock('../../../api/system', () => ({
  getQualityAutomationConfig: mocks.getQualityAutomationConfig,
  updateQualityAutomationConfig: mocks.updateQualityAutomationConfig,
}));

beforeEach(() => {
  mocks.getQualityAutomationConfig.mockResolvedValue({ auto_issue_from_employee_bad: false });
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }),
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('QualityAutomationTab', () => {
  it('loads quality automation config on mount', async () => {
    render(<AntApp><QualityAutomationTab /></AntApp>);
    await waitFor(() => expect(mocks.getQualityAutomationConfig).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('员工差评自动建单')).toBeTruthy();
    expect(await screen.findByLabelText('自动创建质量问题')).toBeTruthy();
  });
});