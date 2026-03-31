export interface ICloudAccount {
  appleId: string;
  sessionId: string;
  active: boolean;
}

export interface LoginRequest {
  appleId: string;
  password: string;
}

export interface LoginResponse {
  sessionId: string;
  requires2fa: boolean;
}
