# AulaFlix

An online course platform for software developers: standalone courses sold once with lifetime access, and the
learning area where Students watch them.

## Language

### People

**Visitor**:
Someone browsing AulaFlix without being signed in.
_Avoid_: guest, anonymous user

**Student**:
A person with an AulaFlix account (UI: "aluno").
_Avoid_: user, customer, member

**Admin**:
An account allowed to author the catalog and perform back-office operations such as Refunds and manual Enrollments.
_Avoid_: instructor, teacher, staff

### Catalog

**Course**:
A standalone product sold once and made of Modules; it is either on sale or coming soon (UI: "em breve").
_Avoid_: product, class, track

**Module**:
An ordered group of Lessons within a Course.
_Avoid_: section, chapter

**Lesson**:
A single video unit inside a Module (UI: "aula"); it is either published or not yet available.
_Avoid_: class, episode, video

**Free lesson**:
The Lesson of an on-sale Course that anyone, Visitors included, may watch.
_Avoid_: preview, sample, trailer

### Access

**Enrollment**:
A Student's right to watch a Course, granted when a payment for it is confirmed or manually by an Admin.
_Avoid_: ownership, subscription, purchase

**Refund**:
The reversal of a Course payment within the 7-day withdrawal period, which also ends the Enrollment it granted.
_Avoid_: chargeback, cancellation

**Waitlist**:
The people, Students or Visitors known only by email, who asked to be told when a coming-soon Course goes on sale.
_Avoid_: wishlist, pre-order, interest list
