import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BatchGroup, Claim, LegItem } from '../models';

export interface SelectedEdge {
  kind: 'cash' | 'memo' | 'original' | 'excluded';
  payer: string;
  receiver: string;
  amount: number;
  currency: string;
  label: string;
  items: LegItem[];
  claim?: Claim;
  reasonCode?: string;
  reasonDetail?: string;
}

interface NodeView {
  code: string;
  x: number;
  y: number;
}

interface EdgeView {
  id: string;
  kind: SelectedEdge['kind'];
  from: string;
  to: string;
  amount: number;
  currency: string;
  label: string;
  x1: number; y1: number; x2: number; y2: number;
  mx: number; my: number;
  items: LegItem[];
  claim?: Claim;
  reasonCode?: string;
  reasonDetail?: string;
}

/**
 * SVG intercompany debt graph.
 * Original view: every eligible/excluded claim as an arrow debtor -> creditor.
 * Netted view: resulting payment legs (cash), zero-amount set-off memos and
 * preserved original legs; click any edge to drill into the source invoices.
 */
@Component({
  selector: 'app-debt-graph',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="graph-wrap">
      <div class="graph-toolbar">
        <div class="seg">
          <button [class.on]="view === 'original'" (click)="view = 'original'">原始债权</button>
          <button [class.on]="view === 'netted'" (click)="view = 'netted'">清算结果</button>
        </div>
        <span class="muted legend">
          <i class="sw cash"></i> 实付腿
          <i class="sw memo"></i> 抵销(零金额)
          <i class="sw orig"></i> 保留原债
          <i class="sw excl"></i> 排除(质押/争议…)
        </span>
      </div>

      <svg [attr.viewBox]="'0 0 720 ' + height" class="graph">
        <defs>
          <marker id="arrow-cash" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0,0 L10,5 L0,10 z" fill="#38bdf8"/>
          </marker>
          <marker id="arrow-memo" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0,0 L10,5 L0,10 z" fill="#a78bfa"/>
          </marker>
          <marker id="arrow-orig" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0,0 L10,5 L0,10 z" fill="#94a3b8"/>
          </marker>
          <marker id="arrow-excl" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0,0 L10,5 L0,10 z" fill="#f87171"/>
          </marker>
        </defs>

        <g *ngFor="let n of nodes">
          <circle [attr.cx]="n.x" [attr.cy]="n.y" r="26"
                  fill="#1e293b" stroke="#475569" stroke-width="2"/>
          <text [attr.x]="n.x" [attr.y]="n.y + 4" text-anchor="middle"
                font-size="15" font-weight="600" fill="#e2e8f0">{{ n.code }}</text>
        </g>

        <g *ngFor="let e of edges" class="edge" (click)="select(e)">
          <line [attr.x1]="e.x1" [attr.y1]="e.y1" [attr.x2]="e.x2" [attr.y2]="e.y2"
                class="ln" [class]="e.kind"
                [attr.marker-end]="markerFor(e.kind)"/>
          <text [attr.x]="e.mx" [attr.y]="e.my - 6" text-anchor="middle"
                class="amt" [class]="e.kind">{{ e.label }}</text>
          <!-- invisible thick hit area -->
          <line [attr.x1]="e.x1" [attr.y1]="e.y1" [attr.x2]="e.x2" [attr.y2]="e.y2"
                class="hit"/>
        </g>
      </svg>
    </div>
  `,
  styles: [`
    .graph-wrap { background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 10px; }
    .graph-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .seg button { border-radius: 6px 0 0 6px; }
    .seg button:last-child { border-radius: 0 6px 6px 0; margin-left: -1px; }
    .seg button.on { background: #0369a1; border-color: #0284c7; color: #fff; }
    .legend i.sw { display: inline-block; width: 18px; height: 3px; border-radius: 2px; margin: 0 4px 0 12px; vertical-align: middle; }
    .sw.cash { background: #38bdf8; } .sw.memo { background: #a78bfa; }
    .sw.orig { background: #94a3b8; } .sw.excl { background: #f87171; }
    .graph { width: 100%; height: auto; }
    .edge { cursor: pointer; }
    .ln { stroke-width: 2; fill: none; }
    .ln.cash { stroke: #38bdf8; } .ln.memo { stroke: #a78bfa; stroke-dasharray: 6 4; }
    .ln.orig { stroke: #94a3b8; } .ln.excl { stroke: #f87171; stroke-dasharray: 2 3; }
    .hit { stroke: transparent; stroke-width: 14; }
    .amt { font-size: 11px; font-variant-numeric: tabular-nums; }
    .amt.cash { fill: #7dd3fc; } .amt.memo { fill: #c4b5fd; }
    .amt.orig { fill: #cbd5e1; } .amt.excl { fill: #fca5a5; }
  `],
})
export class DebtGraphComponent {
  @Input() group!: BatchGroup;
  @Input() claims: Claim[] = [];
  @Output() edgeSelected = new EventEmitter<SelectedEdge>();

  view: 'original' | 'netted' = 'original';
  nodes: NodeView[] = [];
  edges: EdgeView[] = [];
  height = 320;

  private readonly W = 720;
  private readonly H = 320;

  ngOnChanges(): void {
    this.layout();
  }

  markerFor(kind: string): string {
    return { cash: 'url(#arrow-cash)', memo: 'url(#arrow-memo)',
             original: 'url(#arrow-orig)', excluded: 'url(#arrow-excl)' }[kind] ?? '';
  }

  select(e: EdgeView): void {
    this.edgeSelected.emit({
      kind: e.kind, payer: e.from, receiver: e.to, amount: e.amount,
      currency: e.currency, label: e.label, items: e.items,
      claim: e.claim, reasonCode: e.reasonCode, reasonDetail: e.reasonDetail,
    });
  }

  private layout(): void {
    if (!this.group) {
      this.nodes = []; this.edges = []; return;
    }
    const codes = new Set<string>();
    if (this.view === 'netted') {
      this.group.legs.forEach((l) => { codes.add(l.payerCode); codes.add(l.receiverCode); });
    }
    this.claims.forEach((c) => { codes.add(c.debtorCode); codes.add(c.creditorCode); });

    const list = [...codes].sort();
    const n = list.length;
    const r = 120;
    const cx = this.W / 2;
    const cy = this.H / 2;
    this.nodes = list.map((code, i) => {
      const ang = -Math.PI / 2 + (n === 1 ? 0 : (2 * Math.PI * i) / n);
      return { code, x: cx + r * Math.cos(ang), y: cy + r * Math.sin(ang) };
    });
    this.height = this.H;

    const pos = new Map(this.nodes.map((x) => [x.code, { x: x.x, y: x.y }]));
    const excludedByClaim = new Map(this.group.exclusions.map((x) => [x.claimId, x]));

    this.edges = [];
    if (this.view === 'original') {
      let k = 0;
      for (const c of this.claims) {
        const ex = excludedByClaim.get(c.id);
        const kind: SelectedEdge['kind'] = ex ? 'excluded' : 'original';
        this.edges.push(this.edge(`o${k++}`, kind, c.debtorCode, c.creditorCode,
          c.amount, c.currency, this.fmt(c.amount, c.currency) + (ex ? ' ✕' : ''),
          pos, [], c, ex?.reasonCode, ex?.reasonDetail));
      }
    } else {
      let k = 0;
      for (const l of this.group.legs) {
        const kind: SelectedEdge['kind'] =
          l.isOriginal ? 'original' : (l.amount === 0 ? 'memo' : 'cash');
        const text = l.amount === 0
          ? '抵销 0'
          : this.fmt(l.amount, l.settlementCurrency);
        this.edges.push(this.edge(`n${k++}`, kind, l.payerCode, l.receiverCode,
          l.amount, l.settlementCurrency, text, pos, l.items));
      }
    }
  }

  private edge(id: string, kind: SelectedEdge['kind'], from: string, to: string,
               amount: number, currency: string, label: string,
               pos: Map<string, {x:number;y:number}>, items: LegItem[],
               claim?: Claim, reasonCode?: string, reasonDetail?: string): EdgeView {
    const a = pos.get(from)!;
    const b = pos.get(to)!;
    // Shorten at both ends to the node radius.
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const len = Math.hypot(dx, dy) || 1;
    const ux = dx / len;
    const uy = dy / len;
    const rad = 28;
    // Slight perpendicular bow so opposite edges between two nodes don't overlap.
    const bow = from < to ? 0.12 : -0.12;
    const nx = -uy;
    const ny = ux;
    const x1 = a.x + ux * rad;
    const y1 = a.y + uy * rad;
    const x2 = b.x - ux * rad;
    const y2 = b.y - uy * rad;
    return {
      id, kind, from, to, amount, currency, label, items, claim, reasonCode, reasonDetail,
      x1, y1, x2, y2,
      mx: (x1 + x2) / 2 + nx * bow * len,
      my: (y1 + y2) / 2 + ny * bow * len,
    };
  }

  private fmt(v: number, ccy: string): string {
    return `${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${ccy}`;
  }
}
