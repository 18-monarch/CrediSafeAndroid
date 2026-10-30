export type AccountStatus = 'ACTIVE' | 'SUSPENDED';

/** Unknown/missing status is treated as active for legacy rows only. */
export function isAccountSuspended(status: unknown): boolean {
  return status === 'SUSPENDED';
}

export function accountSuspensionMessage(status: unknown): string | null {
  return isAccountSuspended(status)
    ? 'This account is temporarily suspended. Contact CrediSafe support.'
    : null;
}
