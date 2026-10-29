export type AuthDatabaseConflict = {
  status: 409;
  code: 'account_already_exists' | 'device_account_conflict';
  message: string;
};

/**
 * PostgreSQL unique violations can occur when two registration requests race.
 * Convert only known identity constraints; leave unrelated DB failures as
 * service errors and never echo raw database text to clients.
 */
export function classifyAuthUniqueViolation(error: unknown): AuthDatabaseConflict | null {
  if (!error || typeof error !== 'object') return null;

  const candidate = error as { code?: unknown; constraint?: unknown };
  if (candidate.code !== '23505') return null;

  const constraint = typeof candidate.constraint === 'string'
    ? candidate.constraint.toLowerCase()
    : '';

  if (constraint.includes('email')) {
    return {
      status: 409,
      code: 'account_already_exists',
      message: 'An account with this email already exists. Try signing in instead.',
    };
  }

  if (constraint === 'users_pkey' || constraint.endsWith('_pkey')) {
    return {
      status: 409,
      code: 'device_account_conflict',
      message: 'This installation is already associated with another account.',
    };
  }

  return null;
}
