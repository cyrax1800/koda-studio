/**
 * A 3 GB heap keeps the server at about 3.5–4 GB on tiketandroid; with 4 GB it settled at 5 GB. G1 hands memory back
 * to the system after 30 s idle and keeps little free heap committed. Same as package.json's default.
 */
export const DEFAULT_JVM_ARGS = [
  '-Xmx3g',
  '-XX:+UseG1GC',
  '-XX:G1PeriodicGCInterval=30000',
  '-XX:MinHeapFreeRatio=10',
  '-XX:MaxHeapFreeRatio=30',
  '-XX:+UseStringDeduplication',
];
