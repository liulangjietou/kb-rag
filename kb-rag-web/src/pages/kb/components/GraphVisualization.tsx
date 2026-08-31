// Author: owlzhangfq@gmail.com
import { useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { Empty, Typography } from 'antd';
import type { GraphEntity } from '../../../api/types';

interface GraphVisualizationProps {
  /** Already capped to the top-N entities the caller wants drawn (M7-CONTRACTS.md section 2: default 50). */
  entities: GraphEntity[];
  /** Opens the entity's source-chunk drill-down drawer, shared with the entity list table's row action. */
  onEntityClick: (name: string) => void;
}

interface LayoutPoint {
  x: number;
  y: number;
}

const VIEW_WIDTH = 900;
const VIEW_HEIGHT = 560;
const PADDING = 40;
const FORCE_ITERATIONS = 300;

/** Deterministic pseudo-random in [0,1) so re-renders with the same entity set don't jitter. */
function seededRandom(seed: number): () => number {
  let state = seed || 1;
  return () => {
    state = (state * 1103515245 + 12345) & 0x7fffffff;
    return state / 0x7fffffff;
  };
}

/** Small string hash used only to pick a stable hue per freeform entity `type` label. */
function hashString(value: string): number {
  let hash = 0;
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash * 31 + value.charCodeAt(i)) & 0xffffffff;
  }
  return Math.abs(hash);
}

function colorForType(type: string): string {
  const hue = hashString(type || 'unknown') % 360;
  return `hsl(${hue}, 62%, 55%)`;
}

/**
 * Fruchterman-Reingold force-directed layout, computed synchronously over a fixed iteration
 * budget (no external graph/physics library -- see the M7 web report's "简版可视化" section for
 * why this was chosen over @antv/g6: 50 nodes / a few hundred edges settles in well under a
 * millisecond of JS, so a self-contained implementation avoids a multi-hundred-KB dependency and
 * the CSP/bundling questions that come with it).
 */
function computeLayout(nodeIds: string[], edges: Array<{ source: string; target: string }>): Map<string, LayoutPoint> {
  const positions = new Map<string, LayoutPoint>();
  if (nodeIds.length === 0) {
    return positions;
  }
  const rand = seededRandom(nodeIds.length * 97 + edges.length);
  const width = VIEW_WIDTH - PADDING * 2;
  const height = VIEW_HEIGHT - PADDING * 2;
  // Circular initial placement (rather than pure random) so disconnected nodes still spread out
  // evenly before the simulation starts, instead of clumping near the center by chance.
  nodeIds.forEach((id, index) => {
    const angle = (index / nodeIds.length) * Math.PI * 2;
    const radius = (Math.min(width, height) / 2) * (0.5 + 0.5 * rand());
    positions.set(id, {
      x: width / 2 + radius * Math.cos(angle),
      y: height / 2 + radius * Math.sin(angle),
    });
  });

  const area = width * height;
  const k = Math.sqrt(area / nodeIds.length);
  let temperature = width / 10;
  const cooling = temperature / FORCE_ITERATIONS;

  for (let iter = 0; iter < FORCE_ITERATIONS; iter += 1) {
    const disp = new Map<string, LayoutPoint>(nodeIds.map((id) => [id, { x: 0, y: 0 }]));

    for (let i = 0; i < nodeIds.length; i += 1) {
      for (let j = i + 1; j < nodeIds.length; j += 1) {
        const a = nodeIds[i];
        const b = nodeIds[j];
        const pa = positions.get(a)!;
        const pb = positions.get(b)!;
        let dx = pa.x - pb.x;
        let dy = pa.y - pb.y;
        let distance = Math.sqrt(dx * dx + dy * dy) || 0.01;
        dx /= distance;
        dy /= distance;
        const force = (k * k) / distance;
        const da = disp.get(a)!;
        const db = disp.get(b)!;
        da.x += dx * force;
        da.y += dy * force;
        db.x -= dx * force;
        db.y -= dy * force;
      }
    }

    edges.forEach(({ source, target }) => {
      if (source === target) return;
      const pa = positions.get(source);
      const pb = positions.get(target);
      if (!pa || !pb) return;
      let dx = pa.x - pb.x;
      let dy = pa.y - pb.y;
      const distance = Math.sqrt(dx * dx + dy * dy) || 0.01;
      dx /= distance;
      dy /= distance;
      const force = (distance * distance) / k;
      const da = disp.get(source)!;
      const db = disp.get(target)!;
      da.x -= dx * force;
      da.y -= dy * force;
      db.x += dx * force;
      db.y += dy * force;
    });

    nodeIds.forEach((id) => {
      const d = disp.get(id)!;
      const dispLen = Math.sqrt(d.x * d.x + d.y * d.y) || 0.01;
      const limited = Math.min(dispLen, temperature);
      const p = positions.get(id)!;
      p.x = Math.min(width, Math.max(0, p.x + (d.x / dispLen) * limited));
      p.y = Math.min(height, Math.max(0, p.y + (d.y / dispLen) * limited));
    });

    temperature = Math.max(0, temperature - cooling);
  }

  // Shift into the padded viewBox.
  nodeIds.forEach((id) => {
    const p = positions.get(id)!;
    positions.set(id, { x: p.x + PADDING, y: p.y + PADDING });
  });
  return positions;
}

/**
 * Self-implemented lightweight SVG force-directed graph for the top-N entity relationship view
 * (M7-CONTRACTS.md section 2). Nodes are draggable (repositions only, physics does not re-run) so
 * a cluttered auto-layout can be manually untangled; click opens the same entity drill-down drawer
 * the entity list table uses.
 */
export default function GraphVisualization({ entities, onEntityClick }: GraphVisualizationProps) {
  const svgRef = useRef<SVGSVGElement | null>(null);
  const [dragId, setDragId] = useState<string | null>(null);
  const [overridePositions, setOverridePositions] = useState<Map<string, LayoutPoint>>(new Map());

  const nodeIds = useMemo(() => entities.map((e) => e.name), [entities]);
  const nodeSet = useMemo(() => new Set(nodeIds), [nodeIds]);
  const edges = useMemo(
    () =>
      entities.flatMap((entity) =>
        entity.relations
          .filter((rel) => nodeSet.has(rel.target) && rel.target !== entity.name)
          .map((rel) => ({ source: entity.name, target: rel.target, type: rel.type })),
      ),
    [entities, nodeSet],
  );
  const basePositions = useMemo(() => computeLayout(nodeIds, edges), [nodeIds, edges]);

  const positionOf = (id: string): LayoutPoint => overridePositions.get(id) ?? basePositions.get(id) ?? { x: 0, y: 0 };

  const maxWeight = Math.max(1, ...entities.map((e) => e.source_chunk_count));
  const radiusOf = (weight: number) => 8 + Math.sqrt(Math.max(0, weight) / maxWeight) * 16;

  const toSvgPoint = (clientX: number, clientY: number): LayoutPoint => {
    const svg = svgRef.current;
    if (!svg) return { x: 0, y: 0 };
    const point = svg.createSVGPoint();
    point.x = clientX;
    point.y = clientY;
    const ctm = svg.getScreenCTM();
    if (!ctm) return { x: 0, y: 0 };
    const transformed = point.matrixTransform(ctm.inverse());
    return { x: transformed.x, y: transformed.y };
  };

  const handlePointerMove = (e: ReactPointerEvent<SVGSVGElement>) => {
    if (!dragId) return;
    const p = toSvgPoint(e.clientX, e.clientY);
    setOverridePositions((prev) => {
      const next = new Map(prev);
      next.set(dragId, p);
      return next;
    });
  };

  const stopDrag = () => setDragId(null);

  if (entities.length === 0) {
    return <Empty description="暂无实体数据可供可视化" />;
  }

  return (
    <div className="knowledge-graph-visualization">
      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        节点大小 = 来源分片数；可拖拽节点调整位置；点击节点查看该实体的来源分片
      </Typography.Text>
      <svg
        className="knowledge-graph-canvas"
        ref={svgRef}
        viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
        onPointerMove={handlePointerMove}
        onPointerUp={stopDrag}
        onPointerLeave={stopDrag}
      >
        <g stroke="var(--kb-color-border-strong)" strokeWidth={1} opacity={0.72}>
          {edges.map((edge, index) => {
            const a = positionOf(edge.source);
            const b = positionOf(edge.target);
            return <line key={`${edge.source}-${edge.target}-${index}`} x1={a.x} y1={a.y} x2={b.x} y2={b.y} />;
          })}
        </g>
        <g>
          {entities.map((entity) => {
            const p = positionOf(entity.name);
            const r = radiusOf(entity.source_chunk_count);
            return (
              <g
                key={entity.name}
                transform={`translate(${p.x}, ${p.y})`}
                style={{ cursor: 'grab' }}
                onPointerDown={(e) => {
                  e.currentTarget.setPointerCapture(e.pointerId);
                  setDragId(entity.name);
                }}
                onClick={() => onEntityClick(entity.name)}
              >
                <circle r={r} fill={colorForType(entity.type)} stroke="var(--kb-color-surface)" strokeWidth={1.5}>
                  <title>
                    {entity.name}（{entity.type || '未分类'}） · 来源分片 {entity.source_chunk_count}
                  </title>
                </circle>
                <text
                  x={0}
                  y={r + 12}
                  textAnchor="middle"
                  fontSize={11}
                  fill="var(--kb-color-text)"
                  style={{ pointerEvents: 'none', userSelect: 'none' }}
                >
                  {entity.name.length > 8 ? `${entity.name.slice(0, 8)}…` : entity.name}
                </text>
              </g>
            );
          })}
        </g>
      </svg>
    </div>
  );
}
