// Author: owlzhangfq@gmail.com
import { apiGet, apiPost } from './request';
import type { PageResult } from './types';
import type {
  ApproveRegistrationRequest,
  CreateRegistrationRequest,
  CreateRegistrationResponse,
  ListRegistrationReviewsParams,
  RegistrationReviewSummary,
  RejectRegistrationRequest,
  SendRegistrationCodeRequest,
  SendRegistrationCodeResponse,
  VerifyRegistrationEmailRequest,
  VerifyRegistrationEmailResponse,
} from './registrationTypes';

export function sendRegistrationCode(
  payload: SendRegistrationCodeRequest,
): Promise<SendRegistrationCodeResponse> {
  return apiPost<SendRegistrationCodeResponse>('/registrations/verification-code', payload);
}

export function verifyRegistrationEmail(
  payload: VerifyRegistrationEmailRequest,
): Promise<VerifyRegistrationEmailResponse> {
  return apiPost<VerifyRegistrationEmailResponse>('/registrations/verify-email', payload);
}

export function createRegistration(
  payload: CreateRegistrationRequest,
): Promise<CreateRegistrationResponse> {
  return apiPost<CreateRegistrationResponse>('/registrations', payload);
}

export function listRegistrationReviews(
  params: ListRegistrationReviewsParams,
): Promise<PageResult<RegistrationReviewSummary>> {
  return apiGet<PageResult<RegistrationReviewSummary>>('/registration-reviews', params);
}

export function approveRegistration(
  applicationId: string,
  payload: ApproveRegistrationRequest,
): Promise<RegistrationReviewSummary> {
  return apiPost<RegistrationReviewSummary>(`/registration-reviews/${applicationId}/approve`, payload);
}

export function rejectRegistration(
  applicationId: string,
  payload: RejectRegistrationRequest,
): Promise<RegistrationReviewSummary> {
  return apiPost<RegistrationReviewSummary>(`/registration-reviews/${applicationId}/reject`, payload);
}
