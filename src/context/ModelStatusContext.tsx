import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { getModelStatus } from '../api/system';
import type { ModelStatus } from '../api/types';

interface ModelStatusContextValue {
  modelStatus: ModelStatus | null;
  loading: boolean;
  refresh: () => void;
}

const ModelStatusContext = createContext<ModelStatusContextValue | null>(null);

/**
 * Fetches GET /api/v1/system/model-status once when the authenticated app shell mounts
 * (see M1-CONTRACTS.md section 7 "零 Key 处理"). This endpoint requires a Bearer token like
 * every other endpoint besides /auth/login and /actuator, so it is only called after login,
 * not from the public login page.
 */
export function ModelStatusProvider({ children }: { children: ReactNode }) {
  const [modelStatus, setModelStatus] = useState<ModelStatus | null>(null);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(() => {
    setLoading(true);
    getModelStatus()
      .then(setModelStatus)
      .catch(() => undefined)
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const value = useMemo<ModelStatusContextValue>(
    () => ({ modelStatus, loading, refresh }),
    [modelStatus, loading, refresh],
  );

  return <ModelStatusContext.Provider value={value}>{children}</ModelStatusContext.Provider>;
}

export function useModelStatus(): ModelStatusContextValue {
  const ctx = useContext(ModelStatusContext);
  if (!ctx) {
    throw new Error('useModelStatus must be used within ModelStatusProvider');
  }
  return ctx;
}
