# Card payments on Asaas's page, Pix on ours

Asaas offers no hosted card fields and no client-side tokenization, so sending card data through its API keeps our
servers in PCI scope. The card step therefore redirects to an Asaas Checkout, where the Student types the card and
picks the number of installments. Pix stays on our page: it is a direct charge whose QR code and copy-and-paste code
we show ourselves, because paying by Pix from a phone works best without leaving the site. The two methods integrate
differently. Pix needs the Student's CPF, which we send to Asaas without storing it, and a job that deletes a charge
when its Order expires. The card needs callback URLs and correlation through the Checkout id
([research](https://github.com/dev-labs-ai/aulaflix-api/issues/2),
[decision](https://github.com/dev-labs-ai/aulaflix-api/issues/8)).

## Considered Options

- **Card through the Asaas API from our own form**, as the web's prototype form suggests: rejected, since it puts
  our servers in PCI scope.
- **Pix through a Pix-only Asaas Checkout as well**: one integration model, with expiry managed by Asaas. Rejected
  for the extra redirect on the method most buyers use.

## Consequences

- The web's card form and its installment selector go away.
- The return from the Checkout is not proof of payment: only a webhook, confirmed by re-reading the charge, marks an
  Order paid.
