import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

type Profile = {
  id: number;
  name: string;
  password: string;
  showPassword: boolean;
  enteredPassword?: string;
};

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent {
  loginForm: FormGroup;
  addProfileForm: FormGroup;
  profiles: Profile[] = [];
  selectedProfile: Profile | null = null;
  addProfileMode = false;
  isLoading = false;
  errorMessage = '';

  constructor(
    private fb: FormBuilder,
    private authService: AuthService,
    private router: Router
  ) {
    this.loginForm = this.fb.group({
      username: ['', [Validators.required]],
      password: ['', [Validators.required]]
    });

    this.addProfileForm = this.fb.group({
      name: ['', [Validators.required]],
      password: ['', [Validators.required]]
    });

    this.loadProfiles();
  }

  private loadProfiles() {
    const stored = localStorage.getItem('loginProfiles');
    if (stored) {
      this.profiles = JSON.parse(stored);
    } else {
      this.profiles = [
        { id: 1, name: 'user1', password: 'password1', showPassword: false },
        { id: 2, name: 'user2', password: 'password2', showPassword: false }
      ];
      this.saveProfiles();
    }
  }

  private saveProfiles() {
    localStorage.setItem('loginProfiles', JSON.stringify(this.profiles));
  }

  selectProfile(profile: Profile) {
    this.profiles.forEach(p => (p.showPassword = false));
    profile.showPassword = true;
    profile.enteredPassword = profile.password || '';
    this.selectedProfile = profile;
    this.errorMessage = '';
  }

  toggleAddProfile() {
    this.addProfileMode = !this.addProfileMode;
    if (!this.addProfileMode) {
      this.addProfileForm.reset();
    }
  }

  onAddProfileSubmit() {
    if (this.addProfileForm.invalid) {
      return;
    }

    const newProfile = {
      id: Date.now(),
      name: this.addProfileForm.value.name,
      password: this.addProfileForm.value.password,
      showPassword: false,
      enteredPassword: this.addProfileForm.value.password
    };

    this.profiles.push(newProfile);
    this.saveProfiles();
    this.addProfileForm.reset();
    this.addProfileMode = false;
  }

  loginProfile(profile: Profile) {
    if (!profile.enteredPassword) {
      this.errorMessage = 'Please enter password';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    try {
      this.authService.login(profile.name, profile.enteredPassword);
      this.router.navigate(['/dashboard']);
    } catch (error) {
      this.errorMessage = 'Login failed. Please check your credentials.';
    } finally {
      this.isLoading = false;
    }
  }
}
