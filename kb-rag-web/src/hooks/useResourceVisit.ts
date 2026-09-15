import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { recordResourceVisit, type ResourceVisitKind } from '../api/resourceVisit';

/** 只记录本次导航成功展示的资源，重新渲染和后台轮询不算新的访问。 */
export function useResourceVisit(kind: ResourceVisitKind, resourceId: string | undefined, ready: boolean) {
  const location = useLocation();
  const recorded = useRef<string>();
  useEffect(() => {
    if (!ready || !resourceId) return;
    const key = `${location.key}:${kind}:${resourceId}`;
    if (recorded.current === key) return;
    recorded.current = key;
    void recordResourceVisit(kind, resourceId).catch(() => undefined);
  }, [kind, resourceId, ready, location.key]);
}
