import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Agreement, Batch, Claim, FxRate, LegalEntity } from './models';

@Injectable({ providedIn: 'root' })
export class ClearingApiService {
  private readonly base = '/api';

  constructor(private http: HttpClient) {}

  entities(): Observable<LegalEntity[]> {
    return this.http.get<LegalEntity[]>(`${this.base}/entities`);
  }
  agreements(): Observable<Agreement[]> {
    return this.http.get<Agreement[]>(`${this.base}/agreements`);
  }
  claims(): Observable<Claim[]> {
    return this.http.get<Claim[]>(`${this.base}/claims`);
  }
  fxRates(): Observable<FxRate[]> {
    return this.http.get<FxRate[]>(`${this.base}/fx-rates`);
  }
  listBatches(): Observable<Batch[]> {
    return this.http.get<Batch[]>(`${this.base}/batches`);
  }
  getBatch(id: string): Observable<Batch> {
    return this.http.get<Batch>(`${this.base}/batches/${id}`);
  }
  simulate(valuationDate: string, agreementCodes: string[], note: string): Observable<Batch> {
    return this.http.post<Batch>(`${this.base}/batches/simulate`, {
      valuationDate, agreementCodes, note,
    });
  }
  confirm(id: string): Observable<Batch> {
    return this.http.post<Batch>(`${this.base}/batches/${id}/confirm`, {});
  }
  paySimulated(id: string): Observable<Batch> {
    return this.http.post<Batch>(`${this.base}/batches/${id}/pay-simulated`, {});
  }
}
