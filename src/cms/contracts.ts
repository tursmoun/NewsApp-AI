import type { Community, Post } from '../domain/types';

export interface CmsNews extends Post {
  aiState?: 'waiting' | 'done' | 'error';
  aiGroup?: 'normal' | 'doubtful' | 'suspicious';
  aiModel?: string;
  aiErrorCode?: string;
  aiAttempts?: number;
  aiRetryAt?: string;
  aiCheckedAt?: string;
  aiReview?: {
    newsworthiness: 'fits' | 'unclear' | 'off_topic';
    violationRisk: 'low' | 'uncertain' | 'high';
    reasons: string[];
    evidence: string[];
    mediaUnchecked: boolean;
  };
  deleted: boolean;
  mediaIds: string[];
  createdAt: string;
  submittedAt: string;
}

export interface CmsCommunity extends Community {
  official: boolean;
  createdAt: string;
}

export interface CmsUser {
  id: string;
  username: string;
  name: string;
  email: string;
  bio: string;
  role: 'USER' | 'ADMIN';
  verified: boolean;
  blocked: boolean;
  blockReason: string;
  createdAt: string;
}

export interface CmsReport {
  id: string;
  newsId: string;
  title: string;
  reason: string;
  comment: string;
  reporter: string;
  status: string;
  resolution?: string;
  createdAt: string;
}

export interface CmsSummary {
  news: number;
  pending: number;
  reports: number;
  communities: number;
}
