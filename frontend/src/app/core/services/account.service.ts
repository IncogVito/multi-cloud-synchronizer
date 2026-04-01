import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { AccountResponse, LoginResponse, TwoFaRequest } from '../api/generated/model';

export interface LoginRequest {
  appleId: string;
  password: string;
}

export interface TwoFaResponse {
  authenticated: boolean;
  message?: string;
}

export interface AvailableAccountsResponse {
  accounts: Array<{
    id: string;
    name: string;
    appleId: string;
  }>;
}

@Injectable({
  providedIn: 'root'
})
export class AccountService {

  constructor(private apiService: ApiService) { }

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.apiService.post<LoginResponse>('/accounts/login', request);
  }

  submitTwoFa(request: TwoFaRequest): Observable<TwoFaResponse> {
    return this.apiService.post<TwoFaResponse>('/accounts/2fa', request);
  }

  listAccounts(): Observable<AccountResponse[]> {
    return this.apiService.get<AccountResponse[]>('/accounts');
  }

  getAccountStatus(id: string): Observable<AccountResponse> {
    return this.apiService.get<AccountResponse>(`/accounts/${id}/status`);
  }

  deleteAccount(id: string): Observable<void> {
    return this.apiService.delete<void>(`/accounts/${id}`);
  }
}
