import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectedEdge } from './debt-graph.component';

/** Drill-down panel: which original invoices an offset edge consumed, with FX trace. */
@Component({
  selector: 'app-trace-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel" *ngIf="edge">
      <div class="head">
        <h3>边追溯：{{ edge.payer }} → {{ edge.receiver }}</h3>
        <button (click)="closed.emit()">×</button>
      </div>

      <div class="kindline">
        <span class="tag" [class]="edge.kind">{{ kindText }}</span>
        <span class="amt">{{ edge.label }} {{ edge.currency }}</span>
      </div>

      <div class="reason" *ngIf="edge.claim">
        <div><b>发票：</b>{{ edge.claim.invoiceNo }}</div>
        <div class="muted">{{ edge.claim.description }}</div>
        <div><b>原始债务：</b>{{ edge.claim.debtorCode }} 欠 {{ edge.claim.creditorCode }}
          {{ money(edge.claim.amount) }} {{ edge.claim.currency }}
          <span class="tag" [class]="edge.claim.status">{{ edge.claim.status }}</span>
        </div>
      </div>

      <div class="reason" *ngIf="edge.reasonCode">
        <b class="bad">排除原因：{{ edge.reasonCode }}</b>
        <div class="muted">{{ edge.reasonDetail }}</div>
      </div>

      <table *ngIf="edge.items.length">
        <thead>
          <tr>
            <th>原始发票</th><th>方向/侧</th><th class="num">原始金额</th>
            <th class="num">汇率</th><th>汇率时点/来源</th>
            <th class="num">精确换算(6位)</th><th class="num">入账(2位)</th>
            <th class="num">尾差</th><th>尾差归属</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let it of edge.items">
            <td>
              <div>{{ it.invoiceNo }}</div>
              <div class="muted">{{ it.debtorCode }}→{{ it.creditorCode }} · {{ it.claimId }}</div>
            </td>
            <td>
              {{ it.side === 'PAYER' ? '应付侧' : '应收侧' }}
              <span class="muted">({{ it.side === 'PAYER' ? it.debtorCode : it.creditorCode }})</span>
            </td>
            <td class="num">{{ money(it.originalAmount) }} {{ it.originalCurrency }}</td>
            <td class="num">
              <ng-container *ngIf="it.originalCurrency !== edge.currency; else sameCcy">
                {{ it.fxRate | number: '1.6-10' }}
                <span class="muted" *ngIf="it.fxInverted">(取反)</span>
              </ng-container>
              <ng-template #sameCcy><span class="muted">1.000000</span></ng-template>
            </td>
            <td class="muted">
              <ng-container *ngIf="it.fxAsOf; else fxNone">
                {{ it.fxAsOf | date: 'yyyy-MM-dd HH:mm:ss' }} UTC<br>{{ it.fxSource }}
              </ng-container>
              <ng-template #fxNone>同币种</ng-template>
            </td>
            <td class="num">{{ it.convertedExact | number: '1.6-6' }}</td>
            <td class="num">{{ it.convertedBooked | number: '1.2-2' }}</td>
            <td class="num" [class.bad]="it.roundingDiff !== 0">{{ it.roundingDiff | number: '1.6-6' }}</td>
            <td>{{ it.roundingBearerCode || '—' }}</td>
          </tr>
        </tbody>
      </table>

      <p class="muted note" *ngIf="!edge.items.length && !edge.claim">
        该腿为汇总结果，系统按法人净头寸自动生成；点击“清算结果”中带发票的腿可查看抵销明细。
      </p>
    </div>
  `,
  styles: [`
    .panel { background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 12px; margin-top: 10px; }
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .head button { padding: 2px 9px; }
    .kindline { display: flex; gap: 12px; align-items: center; margin-bottom: 10px; }
    .kindline .amt { font-variant-numeric: tabular-nums; }
    .tag { padding: 1px 8px; border-radius: 999px; font-size: 11px; border: 1px solid; }
    .tag.cash { color:#7dd3fc; border-color:#38bdf8; }
    .tag.memo { color:#c4b5fd; border-color:#a78bfa; }
    .tag.original { color:#cbd5e1; border-color:#94a3b8; }
    .tag.excluded, .bad { color:#fca5a5; }
    .tag.PLEDGED, .tag.DISPUTED { color:#fca5a5; border-color:#f87171; }
    .tag.OPEN, .tag.SETTLED { color:#6ee7b7; border-color:#34d399; }
    .reason { background:#0f172a; border:1px solid #334155; border-radius:6px; padding:8px 10px; margin-bottom:8px; }
    .reason div { margin: 2px 0; }
    td, th { vertical-align: top; }
    .note { margin: 8px 2px; }
  `],
})
export class TracePanelComponent {
  @Input() edge: SelectedEdge | null = null;
  @Output() closed = new EventEmitter<void>();

  get kindText(): string {
    return {
      cash: '实付腿（净额结算）',
      memo: '抵销备忘录（零金额，纯追溯）',
      original: '保留的原始债务（未抵销）',
      excluded: '被排除债权',
    }[this.edge?.kind ?? 'cash'];
  }

  money(v: number): string {
    return (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}
