% Build-time only: ask LilyPond to interpret the original score, including
% relative pitches, nested tuplets, voices, clefs, pickups and unfolded repeats.
#(define ns-port (open-output-file (getenv "NOTESPRINT_EXPORT")))
#(setvbuf ns-port 'none)
#(define ns-scores (make-hash-table))
#(define ns-staves (make-hash-table))
#(define ns-next-score 0)
#(define ns-next-staff 0)
#(define (ns-parent context name)
   (if (eq? (ly:context-name context) name) context
       (let ((parent (ly:context-parent context)))
         (if parent (ns-parent parent name) context))))
#(define (ns-main moment) (/ (ly:moment-main-numerator moment) (ly:moment-main-denominator moment)))
#(define (ns-grace moment) (/ (ly:moment-grace-numerator moment) (ly:moment-grace-denominator moment)))
#(define (ns-record engraver event rest?)
   (let* ((ctx (ly:translator-context engraver))
          (score (ns-parent ctx 'Score))
          (staff (ns-parent ctx 'Staff))
          (now (ly:context-current-moment ctx))
          (duration (ly:event-property event 'duration))
          (pitch (ly:event-property event 'pitch #f))
          (signature (ly:context-property ctx 'keyAlterations '()))
          (time (ly:context-property ctx 'timeSignatureFraction '(4 . 4)))
          (articulations (ly:event-property event 'articulations '()))
          (tie? (any (lambda (a) (ly:in-event-class? a 'tie-event)) articulations)))
     (format ns-port "N\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a\t~a~%"
       (hashq-ref ns-scores score -1) (hashq-ref ns-staves staff -1)
       (ly:context-id ctx) (ns-main now) (ns-grace now)
       (ly:context-property ctx 'currentBarNumber 1)
       (ns-main (ly:context-property ctx 'measurePosition (ly:make-moment 0)))
       (ns-main (ly:duration->moment duration)) (ly:duration-log duration) (ly:duration-dot-count duration)
       (if pitch (+ 60 (ly:pitch-semitones pitch)) -1)
       (if pitch (ly:pitch-notename pitch) -1) (if pitch (ly:pitch-octave pitch) -1)
       (if pitch (ly:pitch-alteration pitch) 0)
       (ly:context-property ctx 'clefGlyph "clefs.G")
       (car time) (cdr time)
       (string-join (map (lambda (letter) (number->string (assoc-get letter signature 0))) (iota 7)) ","))
     (when tie? (format ns-port "T\t~a\t~a\t~a\t~a\t~a~%"
       (hashq-ref ns-scores score -1) (hashq-ref ns-staves staff -1) (ly:context-id ctx) (ns-main now)
       (if pitch (+ 60 (ly:pitch-semitones pitch)) -1)))))
\layout {
  \context {
    \Score
    \consists #(lambda (context) (make-engraver
      ((initialize engraver)
        (hashq-set! ns-scores context ns-next-score)
        (set! ns-next-score (+ ns-next-score 1))
        (set! ns-next-staff 0))))
  }
  \context {
    \Staff
    \consists #(lambda (context) (make-engraver
      ((initialize engraver)
        (hashq-set! ns-staves context ns-next-staff)
        (format ns-port "S\t~a\t~a\t~a~%" (hashq-ref ns-scores (ns-parent context 'Score) -1) ns-next-staff (ly:context-id context))
        (set! ns-next-staff (+ ns-next-staff 1)))))
  }
  \context {
    \Voice
    \consists #(lambda (context) (make-engraver
      (listeners
        ((note-event engraver event) (ns-record engraver event #f))
        ((rest-event engraver event) (ns-record engraver event #t))
        ((tie-event engraver event)
          (format ns-port "T\t~a\t~a\t~a\t~a\t-1~%"
            (hashq-ref ns-scores (ns-parent context 'Score) -1)
            (hashq-ref ns-staves (ns-parent context 'Staff) -1)
            (ly:context-id context) (ns-main (ly:context-current-moment context)))))))
  }
}
