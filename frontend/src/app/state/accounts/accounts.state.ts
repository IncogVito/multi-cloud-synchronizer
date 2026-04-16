import { Injectable } from '@angular/core';
import { Action, Selector, State, StateContext } from '@ngxs/store';
import { tap, catchError, EMPTY } from 'rxjs';
import { AccountsService } from '../../core/api/generated/accounts/accounts.service';
import { AccountResponse } from '../../core/api/generated/model/accountResponse';
import { AccountAdded, AccountRemoved, LoadAccounts } from './accounts.actions';

export interface AccountsStateModel {
  accounts: AccountResponse[];
  loading: boolean;
  error: string | null;
}

@State<AccountsStateModel>({
  name: 'accounts',
  defaults: {
    accounts: [],
    loading: false,
    error: null,
  },
})
@Injectable()
export class AccountsState {
  constructor(private readonly accountsService: AccountsService) {}

  @Selector()
  static accounts(state: AccountsStateModel): AccountResponse[] {
    return state.accounts;
  }

  @Selector()
  static loading(state: AccountsStateModel): boolean {
    return state.loading;
  }

  @Selector()
  static error(state: AccountsStateModel): string | null {
    return state.error;
  }

  @Selector()
  static primaryAccount(state: AccountsStateModel): AccountResponse | null {
    const list = state.accounts;
    return list.find(a => a.hasActiveSession) ?? list[0] ?? null;
  }

  /**
   * TODO: Test that LoadAccounts de-duplicates concurrent dispatches (e.g. two components mounting simultaneously).
   * TODO: Test error recovery — verify error state clears on next successful load.
   */
  @Action(LoadAccounts)
  loadAccounts(ctx: StateContext<AccountsStateModel>) {
    ctx.patchState({ loading: true, error: null });
    return this.accountsService.listAccounts().pipe(
      tap(accounts => ctx.patchState({ accounts, loading: false })),
      catchError(err => {
        ctx.patchState({
          loading: false,
          error: err?.message ?? 'Failed to load accounts',
        });
        return EMPTY;
      }),
    );
  }

  @Action(AccountAdded)
  accountAdded(ctx: StateContext<AccountsStateModel>) {
    return ctx.dispatch(new LoadAccounts());
  }

  @Action(AccountRemoved)
  accountRemoved(ctx: StateContext<AccountsStateModel>) {
    return ctx.dispatch(new LoadAccounts());
  }
}
