package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.WaitlistEntryEntity;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntryEntity, Long> {

    /**
     * Puts the email on the Course's Waitlist, unless it already is: the first entry stays as it was. Two joins at
     * once meet at the unique constraint, so neither fails. Each entry takes its own value of the sequence, which steps
     * by 50 to match the entity's allocation; ids may skip, and never collide.
     */
    @Modifying
    @Query(value = """
            insert into waitlist_entries (id, course_id, email, joined_at)
            values (nextval('seq_waitlist_entry'), :courseId, :email, :joinedAt)
            on conflict (course_id, email) do nothing""", nativeQuery = true)
    int insertIfAbsent(@Param("courseId") long courseId, @Param("email") String email,
                       @Param("joinedAt") Instant joinedAt);

    boolean existsByCourseIdAndEmail(long courseId, String email);

    @Modifying
    @Query("delete from WaitlistEntryEntity w where w.course.id = :courseId and w.email = :email")
    int deleteByCourseIdAndEmail(@Param("courseId") long courseId, @Param("email") String email);

    long countByCourseId(long courseId);

    /**
     * The emails on the Course's Waitlist, but those of Students with an active Enrollment in it, all in one query.
     * Entries have no Account link, so a Student is matched by the Account's email, which is stored normalized too.
     */
    @Query("""
            select w.email from WaitlistEntryEntity w
            where w.course.id = :courseId
              and not exists (select e.id from EnrollmentEntity e
                              where e.course.id = :courseId and e.endedAt is null and e.student.email = w.email)
            order by w.id""")
    List<String> findEmailsToNotifyOfLaunch(@Param("courseId") long courseId);

    @Modifying
    @Query("delete from WaitlistEntryEntity w where w.course.id = :courseId")
    int deleteByCourseId(@Param("courseId") long courseId);

    /** Takes the email off every Waitlist at once. */
    @Modifying
    @Query("delete from WaitlistEntryEntity w where w.email = :email")
    int deleteByEmail(@Param("email") String email);

    /** How many entries each Course has, all in one query; a Course without any is left out. */
    @Query("select w.course.id as courseId, count(w) as entries from WaitlistEntryEntity w group by w.course.id")
    List<WaitlistCount> countByCourse();

    interface WaitlistCount {

        long getCourseId();

        long getEntries();
    }
}
