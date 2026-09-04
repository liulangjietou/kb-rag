// Author: owlzhangfq@gmail.com

/** 匿名注册状态；审核完成前服务端不会签发业务会话。 */
export type RegistrationStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface SendRegistrationCodeRequest {
  email: string;
  captcha_proof: string;
}

export interface SendRegistrationCodeResponse {
  resend_after_seconds: number;
}

export interface VerifyRegistrationEmailRequest {
  email: string;
  code: string;
}

export interface VerifyRegistrationEmailResponse {
  registration_ticket: string;
  expires_in_seconds: number;
}

export interface CreateRegistrationRequest {
  registration_ticket: string;
  client_submission_id: string;
  display_name: string;
  team_name?: string | null;
  password: string;
  application_note?: string | null;
}

export interface CreateRegistrationResponse {
  application_id: string;
  email: string;
  status: RegistrationStatus;
  created_at: string;
}

/** 管理员审核列表独立于 UserSummary，避免把未开通申请混入正式账号模型。 */
export interface RegistrationReviewSummary {
  application_id: string;
  email: string;
  display_name: string;
  team_name: string | null;
  application_note: string | null;
  status: RegistrationStatus;
  email_verified_at: string;
  created_at: string;
  reviewed_at?: string | null;
  tenant_id?: string | null;
  role_ids: string[];
  rejection_reason?: string | null;
}

export interface ListRegistrationReviewsParams {
  keyword?: string;
  status?: RegistrationStatus;
  page?: number;
  size?: number;
}

export interface ApproveRegistrationRequest {
  tenant_id: string;
  role_ids: string[];
}

export interface RejectRegistrationRequest {
  reason: string;
}
