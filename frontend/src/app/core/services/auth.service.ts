import { Injectable } from '@angular/core';

const CREDENTIALS_KEY = 'auth_credentials';

export interface AuthCredentials {
  username: string;
  encoded: string;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {

  setCredentials(username: string, password: string): void {
    // Store credentials as Basic Auth for use by AuthInterceptor
    const encoded = btoa(`${username}:${password}`);
    sessionStorage.setItem(CREDENTIALS_KEY, JSON.stringify({ username, encoded }));
  }

  logout(): void {
    sessionStorage.removeItem(CREDENTIALS_KEY);
  }

  isAuthenticated(): boolean {
    return sessionStorage.getItem(CREDENTIALS_KEY) !== null;
  }

  getCredentials(): AuthCredentials | null {
    const raw = sessionStorage.getItem(CREDENTIALS_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

  getUsername(): string | null {
    const credentials = this.getCredentials();
    return credentials?.username || null;
  }
}
