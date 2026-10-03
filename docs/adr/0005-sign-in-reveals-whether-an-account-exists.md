# Sign-in reveals whether an Account exists

`/entrar` asks for the email first, then either asks for the password or offers to create the Account, and sign-up
signs the Student in at once. Both tell anyone whether an email has an Account. We accept that account enumeration
instead of OWASP's non-enumerating flow: knowing that someone has an Account on a course store is worth little to an
attacker, and the email-first flow is easier on people buying a Course. The exposure is contained by throttling and a
CAPTCHA after a threshold
([Abuse protection](https://github.com/dev-labs-ai/aulaflix-api/issues/12)), and password reset stays
non-enumerating ([decision](https://github.com/dev-labs-ai/aulaflix-api/issues/7)).

## Considered Options

- **A non-enumerating flow**: always ask for a password, and open the session only after the Student follows an
  emailed link. Rejected because it adds a step before the first lesson or the checkout.

## Consequences

- Dropping only the email look-up would not help: sign-up still says the email is taken.
- The look-up, sign-up and sign-in endpoints are the cheapest enumeration targets, so they get the strictest limits.
