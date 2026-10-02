import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Batch } from '../models';

@Component({
  selector: 'app-batch-summary',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="cards">
      <div class="card">
        <div class="muted">批次</div>
        <div class="big">{{ batch.id }}</div>
        <div class="muted">{{ batch.valuationDate }}</div>
      </div>
      <div class="card">
        <div class="muted">状态</div>
        <div><span class="tag" [class]="batch.status">{{ statusText }}</span></div>
        <div class="muted" *ngIf="batch.paidSimulatedAt">
          模拟付款 {{ batch.paidSimulatedAt | date: 'MM-dd HH:mm' }}
        </div>
      </div>
      <div class="card">
        <div class="muted">原始笔数 → 清算笔数</div>
        <div class="big"><span class="strike">{{ batch.originalLegCount }}</span>
          → <span class="good">{{ batch.nettedLegCount }}</span></div>
        <div class="muted">纳入 {{ batch.includedClaimCount }} / 排除 {{ batch.excludedClaimCount }}</div>
      </div>
      <div class="card">
        <div class="muted">毛额 / 净应付额</div>
        <div class="big">{{ money(batch.grossAmount) }} / {{ money(batch.netAmount) }}</div>
        <div class="muted">各分组结算币种之和（仅口径汇总）</div>
      </div>
    </div>
  `,
  styles: [`
    .cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 10px 0; }
    .card { background:#1e293b; border:1px solid #334155; border-radius:8px; padding:10px 12px; }
    .big { font-size: 16px; font-weight: 600; margin: 4px 0; font-variant-numeric: tabular-nums; }
    .strike { text-decoration: line-through; color:#94a3b8; }
    .good { color:#34d399; }
    .tag { padding:1px 8px; border-radius:999px; font-size:11px; border:1px solid; }
    .tag.SIMULATED { color:#7dd3fc; border-color:#38bdf8; }
    .tag.CONFIRMED { color:#6ee7b7; border-color:#34d399; }
    .tag.PAID_SIMULATED { color:#fcd34d; border-color:#fbbf24; }
  `],
})
export class BatchSummaryComponent {
  @Input() batch!: Batch;

  get statusText(): string {
    return { SIMULATED: '试算方案', CONFIRMED: '已确认', PAID_SIMULATED: '模拟付款完成' }[this.batch.status];
  }

  money(v: number): string {
    return (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}
