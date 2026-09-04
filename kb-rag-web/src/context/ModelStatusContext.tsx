// Author: owlzhangfq@gmail.com
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { getModelStatus } from '../api/system';
import type { ModelStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { PERMISSIONS } from '../auth/permissions';

interface ModelStatusContextValue {
  modelStatus: ModelStatus | null;
  loading: boolean;
  error: boolean;
  refresh: () => void;
}

const ModelStatusContext = createContext<ModelStatusContextValue | null>(null);

/**
 * 已登录应用外壳挂载时最多读取一次 GET /api/v1/system/model-status（见 M1-CONTRACTS.md
 * 第 7 节“零 Key 处理”）。该接口与登录、健康检查之外的接口一样需要会话，因此只在登录后且
 * 当前角色有权读取系统或知识库状态时调用。其他角色得到明确的空闲上下文，不产生必然的 403。
 */
export function ModelStatusProvider({ children }: { children: ReactNode }) {
  const { canAny } = useAuth();
  const mayReadModelStatus = canAny([PERMISSIONS.SYSTEM_CONFIG, PERMISSIONS.KB_READ]);
  const [modelStatus, setModelStatus] = useState<ModelStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const requestSequenceRef = useRef(0);

  const refresh = useCallback(() => {
    const sequence = ++requestSequenceRef.current;
    if (!mayReadModelStatus) {
      setModelStatus(null);
      setLoading(false);
      setError(false);
      return;
    }
    setLoading(true);
    setError(false);
    getModelStatus()
      .then((status) => {
        if (requestSequenceRef.current === sequence) {
          setModelStatus(status);
          setError(false);
        }
      })
      .catch(() => {
        if (requestSequenceRef.current === sequence) {
          setModelStatus(null);
          setError(true);
        }
      })
      .finally(() => {
        if (requestSequenceRef.current === sequence) setLoading(false);
      });
  }, [mayReadModelStatus]);

  useEffect(() => {
    refresh();
    return () => {
      requestSequenceRef.current += 1;
    };
  }, [refresh]);

  const value = useMemo<ModelStatusContextValue>(
    () => ({ modelStatus, loading, error, refresh }),
    [modelStatus, loading, error, refresh],
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
