# AulaFlix

An online course platform for software developers: standalone courses sold once with lifetime access, and the
learning area where Students watch them.

## Language

### People

**Visitor**:
Someone browsing AulaFlix without being signed in.
_Avoid_: guest, anonymous user

**Account**:
The email and password someone signs in with; it belongs to either a Student or an Admin, never both.
_Avoid_: user, login, profile

**Student**:
A person with an Account who buys and watches Courses (UI: "aluno").
_Avoid_: user, customer, member

**Admin**:
An Account that authors the catalog and performs back-office operations such as Refunds and manual Enrollments; it
is never a Student.
_Avoid_: instructor, teacher, staff

### Catalog

**Course**:
A standalone product sold once and made of Modules; it moves forward from Draft to coming soon (UI: "em breve") to
on sale, and never back.
_Avoid_: product, class, track

**Draft**:
The state of a Course that only Admins can see, while it is being put together.
_Avoid_: hidden, unpublished

**Area**:
The field of software development a Course belongs to, used to filter the catalog.
_Avoid_: category, track

**Planned topics**:
The topics announced for a coming-soon Course before its Modules are shown (UI: "Conteúdo previsto").
_Avoid_: coverage, syllabus

**Module**:
An ordered group of Lessons within a Course.
_Avoid_: section, chapter

**Lesson**:
A single video unit inside a Module (UI: "aula"); once published it stays published, and until then it shows as
"Em breve".
_Avoid_: class, episode, video

**Free lesson**:
The one published Lesson of an on-sale Course that anyone, Visitors included, may watch.
_Avoid_: preview, sample, trailer

**Syllabus**:
The ordered Modules and Lessons of an on-sale Course as everyone sees them, "Em breve" Lessons included (UI:
"Ementa"); it takes over from the Planned topics once the Course goes on sale.
_Avoid_: curriculum, outline, course content

### Access

**Order**:
A Student's request to buy one Course by one payment method, at the price fixed when it was placed (UI: "pedido").
_Avoid_: purchase, sale, transaction

**Enrollment**:
A Student's right to watch a Course, granted when an Order for it is paid or manually by an Admin.
_Avoid_: ownership, subscription, purchase

**Refund**:
The reversal of an Order's payment at the Student's request, as the 7-day guarantee promises, which also ends the
Enrollment it granted.
_Avoid_: chargeback, cancellation

**Duplicate payment**:
The payment of an Order that granted no Enrollment because the Student already had an active one for that Course;
an Admin refunds it by hand.
_Avoid_: double charge, duplicate order

**Waitlist**:
The people, Students or Visitors known only by email, who asked to be told when a coming-soon Course goes on sale;
it is used up when that Course goes on sale.
_Avoid_: wishlist, pre-order, interest list

### Learning

**Progress**:
The published Lessons of a Course that a Student has marked as completed (UI: "aulas concluídas"); it belongs to the
Student rather than to an Enrollment, so it outlives one.
_Avoid_: completion, watch history

**Caught up**:
A Student's standing in a Course when every published Lesson is completed but some Lessons are still "Em breve"
(UI: "aguardando novas aulas").
_Avoid_: up to date, done

**Finished**:
A Student's standing in a Course when every one of its Lessons is completed (UI: "finalizado").
_Avoid_: completed course, done

**Resume lesson**:
The Lesson a Student is taken to when they continue a Course (UI: "Continuar de onde parou").
_Avoid_: last lesson, current lesson
