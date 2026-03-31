import { Injectable } from '@angular/core';

const CREDENTIALS_KEY = 'auth_credentials';

@Injectable({
  providedIn: 'root'
})
export class AuthService {

  login(username: string, password: string): void {
    // TODO: Implement - call backend auth endpoint and validate credentials
    const encoded = btoa(`${username}:${password}`);
    sessionStorage.setItem(CREDENTIALS_KEY, JSON.stringify({ username, encoded }));
  }

  logout(): void {
    // TODO: Implement - invalidate session on backend
    sessionStorage.removeItem(CREDENTIALS_KEY);
  }

  isAuthenticated(): boolean {
    // TODO: Implement - validate token/session with backend
    return sessionStorage.getItem(CREDENTIALS_KEY) !== null;
  }

  getCredentials(): { username: string; encoded: string } | null {
    // TODO: Implement - return current credentials or null
    const raw = sessionStorage.getItem(CREDENTIALS_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }
}
