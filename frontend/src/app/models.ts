export interface LegalEntity {
  code: string;
  name: string;
  functionalCurrency: string;
  active: boolean;
}

export interface Agreement {
  code: string;
  name: string;
  allowsNetting: boolean;
  crossCurrency: boolean;
  settlementCurrency: string;
  roundingBearerCode: string;
  members: string[];
  currencies: string[];
  effectiveFrom: string;
  effectiveTo?: string;
  fxAsOf?: string;
}

export interface Claim {
  id: string;
  invoiceNo: string;
  agreementCode: string;
  debtorCode: string;
  creditorCode: string;
  amount: number;
  currency: string;
  status: 'OPEN' | 'PLEDGED' | 'DISPUTED' | 'SETTLED';
  invoiceDate: string;
  dueDate?: string;
  description: string;
  offsetBatchId?: string;
}

export interface FxRate {
  baseCurrency: string;
  quoteCurrency: string;
  asOf: string;
  rate: number;
  source: string;
}

export interface LegItem {
  id: string;
  claimId: string;
  invoiceNo: string;
  debtorCode: string;
  creditorCode: string;
  side: 'PAYER' | 'RECEIVER';
  originalAmount: number;
  originalCurrency: string;
  fxRate: number;
  fxAsOf?: string;
  fxSource?: string;
  fxInverted: boolean;
  convertedExact: number;
  convertedBooked: number;
  roundingDiff: number;
  roundingBearerCode: string;
}

export interface Exclusion {
  id: string;
  claimId: string;
  invoiceNo: string;
  reasonCode: string;
  reasonDetail: string;
}

export interface BatchLeg {
  id: string;
  payerCode: string;
  receiverCode: string;
  amount: number;
  settlementCurrency: string;
  isOriginal: boolean;
  isMemo: boolean;
  amountExact?: number;
  residualAdjustment: number;
  seqNo: number;
  paidSimulatedAt?: string;
  items: LegItem[];
}

export interface BatchGroup {
  id: string;
  agreementCode: string;
  agreementName: string;
  settlementCurrency: string;
  crossCurrency: boolean;
  passThrough: boolean;
  originalLegCount: number;
  nettedLegCount: number;
  grossAmount: number;
  netAmount: number;
  roundingDiffTotal: number;
  roundingResidual: number;
  roundingBearerCode: string;
  legs: BatchLeg[];
  exclusions: Exclusion[];
}

export interface Batch {
  id: string;
  valuationDate: string;
  status: 'SIMULATED' | 'CONFIRMED' | 'PAID_SIMULATED';
  incomingClaimCount: number;
  includedClaimCount: number;
  excludedClaimCount: number;
  originalLegCount: number;
  nettedLegCount: number;
  grossAmount: number;
  netAmount: number;
  createdAt: string;
  confirmedAt?: string;
  paidSimulatedAt?: string;
  note?: string;
  groups: BatchGroup[];
}
