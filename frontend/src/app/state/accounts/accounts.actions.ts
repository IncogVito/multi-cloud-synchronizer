import { AccountResponse } from '../../core/api/generated/model/accountResponse';

export class LoadAccounts {
  static readonly type = '[Accounts] Load all accounts';
}

/**
 * Optimistically marks an account as added.
 * The store re-fetches the full list from the server to get the canonical state.
 */
export class AccountAdded {
  static readonly type = '[Accounts] Account added — reload list';
}

/**
 * Optimistically removes an account from the local list.
 * The store re-fetches the full list from the server after removal.
 */
export class AccountRemoved {
  static readonly type = '[Accounts] Account removed — reload list';
}
