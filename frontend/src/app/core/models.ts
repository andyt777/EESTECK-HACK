export type Category = 'METER_READING' | 'FAULT_RECORD' | 'CONNECTION_DOCUMENT' | 'DRONE_VIDEO';
export type StorageType = 'DATABASE' | 'S3' | 'FTP';
export type DataOrigin = 'REAL' | 'SIMULATED';
export type FileStatus =
  | 'RECEIVED' | 'ANALYZING' | 'CATEGORIZED' | 'DESTINATION_SELECTED' | 'TRANSFERRING'
  | 'TRANSFER_RESUMED' | 'STORED_VERIFIED' | 'PENDING' | 'TRANSFER_FAILED';
export type Integrity = 'UNVERIFIED' | 'VERIFIED_INTACT' | 'MISMATCH';

export const CATEGORIES: Category[] = ['METER_READING', 'FAULT_RECORD', 'CONNECTION_DOCUMENT', 'DRONE_VIDEO'];
export const STORAGES: StorageType[] = ['DATABASE', 'S3', 'FTP'];
export const FILE_STATUSES: FileStatus[] = [
  'RECEIVED', 'ANALYZING', 'CATEGORIZED', 'DESTINATION_SELECTED', 'TRANSFERRING', 'TRANSFER_RESUMED',
  'STORED_VERIFIED', 'PENDING', 'TRANSFER_FAILED',
];
export const INTEGRITIES: Integrity[] = ['VERIFIED_INTACT', 'UNVERIFIED', 'MISMATCH'];
export const TERMINAL: string[] = ['STORED_VERIFIED', 'PENDING', 'TRANSFER_FAILED'];

export interface Page<T> { items: T[]; page: number; size: number; total: number; }

export interface FileSummary {
  id: string; name: string; category: Category; contentType: string | null; sizeBytes: number;
  receivedAt: string; storedAt: string | null; status: FileStatus; integrityStatus: Integrity;
  storage: StorageType | null; dataOrigin: DataOrigin; source: string; sensitivity: string;
  encrypted: boolean; uploadedBy: string | null; zone: string | null; station: string | null;
  equipment: string | null; meterId: string | null; eventType: string | null; eventTime: string | null;
}

export interface CategorySummary {
  category: Category; label: string; fileCount: number; totalBytes: number; lastReceivedAt: string | null;
  pending: number; mismatch: number; transferFailed: number; inProgress: number;
  byStorage: Record<string, number>; recent: FileSummary[];
}

export interface MeterStats {
  readingsLastMinute: number; readingsPerMinute: number; readingsStored: number; activeMeters: number;
  gaps: number; duplicatesRejected: number; duplicatesWritten: number; lastReadingAt: string | null;
  reconciledAt: string | null; dataOrigin: DataOrigin | null;
}

export interface StorageHealth {
  storage: StorageType; label: string; status: 'UP' | 'DOWN'; lastCheck: string | null; latencyMs: number | null;
}

export interface LiveEvent {
  fileId: string; category: Category | null; status: string; storage: StorageType | null; message: string | null;
  bytesTransferred: number | null; totalBytes: number | null; timestamp: string; traceId: string | null;
}

export interface Reason { code: string; kind: 'POSITIVE' | 'NEGATIVE' | 'INFO'; text: string; }
export interface Candidate {
  storage: StorageType; role: string; eligible: boolean; available: boolean; selected: boolean; score: number;
  factors: Record<string, { value: number; weight: number }>; reasons: Reason[];
}

export interface DecisionView {
  id?: string; fileId?: string; category?: Category; fileStatus?: FileStatus;
  policyVersion: string; detection: any; features: any; candidates: Candidate[] | null;
  selectedStorage: StorageType | null; fallbackUsed: boolean; fallbackReason: string | null;
  explanationRo?: string | null; explanation?: string | null; decidedAt: string;
}

export interface DecisionSummary {
  id: string; fileId: string; fileName: string; category: Category; fileStatus: FileStatus; sizeBytes: number;
  policyVersion: string; selectedStorage: StorageType | null; fallbackUsed: boolean; fallbackReason: string | null;
  explanation: string | null; decidedAt: string;
}

export interface Passport {
  identity: {
    id: string; name: string; category: Category; categoryLabel: string; contentType: string | null;
    sizeBytes: number; receivedAt: string; source: string; uploadedBy: string | null; dataOrigin: DataOrigin;
    sensitivity: string; encrypted: boolean; traceId: string | null;
  };
  status: FileStatus; statusLabel: string; context: Record<string, any>;
  journey: { step: number; key: string; label: string; done: boolean; at: string | null }[];
  storage: { type: StorageType; label: string; storedAt: string | null } | null;
  decision: DecisionView | null;
  integrity: { status: Integrity; label: string; sha256: string | null; sha256Stored: string | null; lastVerifiedAt: string | null };
  audit: Page<{ id: number; type: string; label: string; actor: string | null; details: any; at: string }>;
  actions: string[];
}

export interface VerifyResult {
  fileId: string; intact: boolean; integrityStatus: Integrity; expectedSha256: string; actualSha256: string;
  verifiedAt: string; storage: StorageType; message: string;
}

export interface IncidentSummary {
  id: string; code: string; type: string; typeLabel: string; severity: string; severityLabel: string;
  status: string; statusLabel: string; certainty: string | null; source: string | null; zone: string | null;
  station: string | null; asset: string | null; title: string | null; description: string | null;
  owner: string | null; createdAt: string; updatedAt: string | null; resolvedAt: string | null;
  resolution: string | null; fileCount: number; notificationCount: number; unconfirmedCount: number;
  dataOrigin: DataOrigin;
}

export interface IncidentNotification {
  id: string; incidentId: string; incidentCode: string; kind: 'ALERT' | 'UPDATE'; recipientName: string;
  role: string; roleLabel: string; phoneMasked: string | null; channel: string; mode: string; status: string;
  statusLabel: string; level: number | null; body: string; createdAt: string; sentAt: string | null;
  deliveredAt: string | null; confirmedAt: string | null; confirmedBy: string | null; escalatedAt: string | null;
  escalationDueAt: string | null;
}

export interface IncidentDetail {
  incident: IncidentSummary;
  files: { fileId: string; name: string; category: Category; integrityStatus: Integrity }[];
  timeline: { id: string | number; at: string; actor: string | null; kind: string; message: string }[];
  notifications: IncidentNotification[];
  allowedTransitions: string[];
}

export interface IncidentConfig {
  mode: string; realChannelConfigured: boolean; escalationSeconds: Record<string, number>;
  types: { type: string; label: string; manual: boolean }[]; severities: { value: string; label: string }[];
}

export interface IncidentEvent { kind: string; incident: IncidentSummary | null; notification: IncidentNotification | null; }

export interface Finding {
  id: string; type: string; title?: string | null; meterId: string | null; post: string | null; station: string | null;
  region: string | null; zone?: string | null; windowStart: string | null; windowEnd: string | null;
  observations: string | string[] | null; observation?: string | null; evidence: any; confidence: any;
  humanVerificationRequired: boolean | string | null; humanCheck?: string | null; dataOrigin: DataOrigin;
  status: string; createdAt: string;
}

export interface ChatResponse {
  answer: string; steps: { tool: string; input: any; outputSummary: string }[];
  citations: { label: string; href: string }[]; dataOrigin: DataOrigin | null; provider: string | null;
}

export interface ChatContext { fileId?: string; incidentId?: string; findingId?: string; }
