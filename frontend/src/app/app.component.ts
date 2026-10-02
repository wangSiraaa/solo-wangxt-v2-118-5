import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ClearingApiService } from './api.service';
import { Agreement, Batch, BatchGroup, Claim, LegalEntity } from './models';
import { DebtGraphComponent, SelectedEdge } from './components/debt-graph.component';
import { TracePanelComponent } from './components/trace-panel.component';
import { BatchSummaryComponent } from './components/batch-summary.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    DebtGraphComponent, TracePanelComponent, BatchSummaryComponent,
  ],
  template: `
    <div class="page">
      <header>
        <h1>集团内部债务清算试算台</h1>
        <span class="muted">按协议边界 × 币种分组 · BigDecimal 净额 · 全程不接真实银行</span>
      </header>

      <div class="bank-banner">
        🔒 本系统仅做试算与模拟：确认只登记抵销关系，“付款”仅写入模拟付款时间戳，
        任何环节都不会向银行发送真实指令。
      </div>

      <!-- ---------------- 试算控制区 ---------------- -->
      <section class="panel">
        <h2>新建清算试算批次</h2>
        <div class="controls">
          <label>估值日 <input type="date" [(ngModel)]="valuationDate"></label>
          <label>协议
            <select [(ngModel)]="selectedAgreement" (ngModelChange)="onAgreementChange()">
              <option value="">全部生效协议</option>
              <option *ngFor="let a of agreements" [value]="a.code">
                {{ a.code }} · {{ a.name }}
                {{ a.allowsNetting ? '' : '（禁止抵销）' }}
              </option>
            </select>
          </label>
          <input class="note" placeholder="备注（可选）" [(ngModel)]="note">
          <button class="primary" (click)="runSimulation()" [disabled]="loading">
            {{ loading ? '试算中…' : '生成试算方案（不改动债权）' }}
          </button>
        </div>
        <div class="muted hint" *ngIf="selectedAgreementObj() as a">
          当前协议：成员 {{ a.members.join('、') || '—' }}；
          币种 {{ a.currencies.join('、') || '不限' }}；
          跨币种 {{ a.crossCurrency ? '允许（结算 ' + a.settlementCurrency + '）' : '不允许' }}；
          尾差承担人 {{ a.roundingBearerCode || '—' }}。
        </div>
      </section>

      <!-- ---------------- 批次列表 ---------------- -->
      <section class="panel" *ngIf="batches.length">
        <h2>清算批次</h2>
        <table>
          <thead>
          <tr>
            <th>批次号</th><th>估值日</th><th>状态</th>
            <th class="num">纳入/排除</th><th class="num">原始→清算</th>
            <th class="num">净应付额</th><th></th>
          </tr>
          </thead>
          <tbody>
          <tr *ngFor="let b of batches" [class.active]="current?.id === b.id">
            <td>{{ b.id }}</td>
            <td>{{ b.valuationDate }}</td>
            <td><span class="tag" [class]="b.status">{{ statusText(b.status) }}</span></td>
            <td class="num">{{ b.includedClaimCount }} / {{ b.excludedClaimCount }}</td>
            <td class="num">{{ b.originalLegCount }} → {{ b.nettedLegCount }}</td>
            <td class="num">{{ money(b.netAmount) }}</td>
            <td><button (click)="openBatch(b.id)">打开</button></td>
          </tr>
          </tbody>
        </table>
      </section>

      <!-- ---------------- 当前批次 ---------------- -->
      <ng-container *ngIf="current">
        <app-batch-summary [batch]="current"></app-batch-summary>

        <div class="lifecycle">
          <button class="confirm" (click)="confirm()"
                  [disabled]="current.status !== 'SIMULATED' || busy">
            确认本方案（登记抵销、标记原债权）
          </button>
          <button class="pay" (click)="paySimulated()"
                  [disabled]="current.status !== 'CONFIRMED' || busy">
            模拟付款（仅打标，不发银行）
          </button>
          <span class="muted" *ngIf="current.status === 'PAID_SIMULATED'">
            ✓ 已完成模拟付款，无真实资金移动。
          </span>
        </div>

        <div class="group-tabs">
          <button *ngFor="let g of current.groups; let i = index"
                  [class.on]="activeGroupId === g.id" (click)="selectGroup(g)">
            {{ g.agreementCode }} · {{ g.settlementCurrency }}
            <span class="muted">({{ g.originalLegCount }}→{{ g.nettedLegCount }})</span>
          </button>
        </div>

        <ng-container *ngIf="activeGroup as g">
          <section class="panel">
            <h2>
              {{ g.agreementName }}
              <span class="tag" [class]="g.passThrough ? 'PASS' : 'NET'">
                {{ g.passThrough ? '禁止抵销 · 原债保留' : (g.crossCurrency ? '跨币种净额' : '同币种净额') }}
              </span>
            </h2>
            <div class="muted group-meta">
              结算币种 {{ g.settlementCurrency }}；
              毛额 {{ money(g.grossAmount) }}，净应付 {{ money(g.netAmount) }}；
              原始 {{ g.originalLegCount }} 笔 → 现金腿 {{ g.nettedLegCount }} 笔；
              逐笔尾差合计 {{ g.roundingDiffTotal }}（承担人 {{ g.roundingBearerCode || '—' }}）。
            </div>

            <app-debt-graph [group]="g" [claims]="claimsForGroup(g)"
                            (edgeSelected)="onEdge($event)"></app-debt-graph>
            <app-trace-panel [edge]="selectedEdge" (closed)="selectedEdge = null"></app-trace-panel>
          </section>

          <!-- 结果腿清单 -->
          <section class="panel">
            <h3>清算腿明细（点击图边可追溯到原始发票）</h3>
            <table>
              <thead>
              <tr>
                <th>#</th><th>类型</th><th>付款方</th><th>收款方</th>
                <th class="num">金额</th><th class="num">精确值</th>
                <th>发票数</th><th>模拟付款时间</th>
              </tr>
              </thead>
              <tbody>
              <tr *ngFor="let l of g.legs">
                <td>{{ l.seqNo }}</td>
                <td>
                  <span class="tag" [class]="legClass(l)">{{ legText(l) }}</span>
                </td>
                <td>{{ l.payerCode }}</td>
                <td>{{ l.receiverCode }}</td>
                <td class="num">{{ money(l.amount) }} {{ l.settlementCurrency }}</td>
                <td class="num muted">{{ l.amountExact }}</td>
                <td>{{ l.items.length }}</td>
                <td class="muted">{{ l.paidSimulatedAt ? (l.paidSimulatedAt | date: 'MM-dd HH:mm:ss') : '—' }}</td>
              </tr>
              </tbody>
            </table>
          </section>

          <!-- 排除清单 -->
          <section class="panel" *ngIf="g.exclusions.length">
            <h3 class="bad">排除的债权（不参与本次清算）</h3>
            <table>
              <thead>
              <tr><th>发票</th><th>债权</th><th>原因码</th><th>说明</th></tr>
              </thead>
              <tbody>
              <tr *ngFor="let ex of g.exclusions">
                <td>{{ ex.invoiceNo }}</td>
                <td>{{ ex.claimId }}</td>
                <td><span class="tag DISPUTED">{{ ex.reasonCode }}</span></td>
                <td class="muted">{{ ex.reasonDetail }}</td>
              </tr>
              </tbody>
            </table>
          </section>
        </ng-container>
      </ng-container>

      <p class="muted foot" *ngIf="!current && !loading">
        尚无批次。选择估值日与协议后点击“生成试算方案”。
      </p>
    </div>
  `,
  styles: [`
    .page { max-width: 1180px; margin: 0 auto; padding: 18px 20px 60px; }
    header { margin-bottom: 12px; }
    header h1 { font-size: 22px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 14px 16px; margin: 14px 0; }
    .panel h2 { margin-bottom: 10px; font-size: 16px; }
    .panel h3 { margin-bottom: 8px; font-size: 14px; }
    .controls { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .controls label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--muted); }
    .controls .note { width: 240px; }
    .hint { margin-top: 8px; font-size: 12px; }
    table tr.active { background: rgba(56,189,248,.08); }
    .lifecycle { display: flex; gap: 10px; align-items: center; margin: 6px 0 12px; }
    .group-tabs { display: flex; gap: 6px; flex-wrap: wrap; margin: 8px 0; }
    .group-tabs button.on { background: #0369a1; border-color: #0284c7; color: #fff; }
    .group-meta { margin-bottom: 10px; font-size: 12.5px; }
    .tag { padding: 1px 8px; border-radius: 999px; font-size: 11px; border: 1px solid var(--line); }
    .tag.SIMULATED { color: var(--accent); border-color: var(--accent); }
    .tag.CONFIRMED { color: var(--good); border-color: var(--good); }
    .tag.PAID_SIMULATED { color: var(--warn); border-color: var(--warn); }
    .tag.NET { color: #7dd3fc; border-color: #38bdf8; }
    .tag.PASS { color: #cbd5e1; border-color: #94a3b8; }
    .tag.CASH { color: #7dd3fc; border-color: #38bdf8; }
    .tag.MEMO { color: #c4b5fd; border-color: #a78bfa; }
    .tag.LEGORIG { color: #cbd5e1; border-color: #94a3b8; }
    .bad { color: var(--bad); }
    .foot { margin-top: 20px; }
  `],
})
export class AppComponent implements OnInit {
  entities: LegalEntity[] = [];
  agreements: Agreement[] = [];
  claims: Claim[] = [];
  batches: Batch[] = [];

  valuationDate = '2026-09-30';
  selectedAgreement = '';
  note = '';
  loading = false;
  busy = false;

  current: Batch | null = null;
  activeGroupId = '';
  selectedEdge: SelectedEdge | null = null;

  constructor(private api: ClearingApiService) {}

  ngOnInit(): void {
    this.api.entities().subscribe((x) => (this.entities = x));
    this.api.agreements().subscribe((x) => (this.agreements = x));
    this.api.claims().subscribe((x) => (this.claims = x));
    this.api.listBatches().subscribe((x) => (this.batches = x));
  }

  selectedAgreementObj(): Agreement | undefined {
    return this.agreements.find((a) => a.code === this.selectedAgreement);
  }

  onAgreementChange(): void {
    this.selectedEdge = null;
  }

  get activeGroup(): BatchGroup | null {
    return this.current?.groups.find((g) => g.id === this.activeGroupId) ?? null;
  }

  claimsForGroup(g: BatchGroup): Claim[] {
    return this.claims.filter(
      (c) => c.agreementCode === g.agreementCode
        && (g.crossCurrency || c.currency === g.settlementCurrency),
    );
  }

  runSimulation(): void {
    this.loading = true;
    const codes = this.selectedAgreement ? [this.selectedAgreement] : [];
    this.api.simulate(this.valuationDate, codes, this.note).subscribe({
      next: (b) => {
        this.loading = false;
        this.batches = [b, ...this.batches];
        this.openBatch(b.id);
      },
      error: () => (this.loading = false),
    });
  }

  openBatch(id: string): void {
    this.api.getBatch(id).subscribe((b) => {
      this.current = b;
      this.activeGroupId = b.groups[0]?.id ?? '';
      this.selectedEdge = null;
    });
  }

  selectGroup(g: BatchGroup): void {
    this.activeGroupId = g.id;
    this.selectedEdge = null;
  }

  onEdge(e: SelectedEdge): void {
    this.selectedEdge = e;
  }

  confirm(): void {
    if (!this.current) {
      return;
    }
    this.busy = true;
    this.api.confirm(this.current.id).subscribe({
      next: (b) => {
        this.busy = false;
        this.patchBatch(b);
        this.api.claims().subscribe((x) => (this.claims = x));
      },
      error: () => (this.busy = false),
    });
  }

  paySimulated(): void {
    if (!this.current) {
      return;
    }
    this.busy = true;
    this.api.paySimulated(this.current.id).subscribe({
      next: (b) => {
        this.busy = false;
        this.patchBatch(b);
      },
      error: () => (this.busy = false),
    });
  }

  private patchBatch(b: Batch): void {
    this.current = b;
    const i = this.batches.findIndex((x) => x.id === b.id);
    if (i >= 0) {
      this.batches[i] = b;
    }
  }

  statusText(s: string): string {
    return { SIMULATED: '试算', CONFIRMED: '已确认', PAID_SIMULATED: '模拟已付' }[s] ?? s;
  }

  legClass(l: { isOriginal: boolean; amount: number }): string {
    return l.isOriginal ? 'LEGORIG' : l.amount === 0 ? 'MEMO' : 'CASH';
  }

  legText(l: { isOriginal: boolean; amount: number }): string {
    return l.isOriginal ? '原债保留' : l.amount === 0 ? '抵销' : '实付';
  }

  money(v: number): string {
    return (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}
