/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.consensus.ibft.validation;

import tech.pegasys.pantheon.consensus.ibft.ConsensusRoundIdentifier;
import tech.pegasys.pantheon.consensus.ibft.blockcreation.ProposerSelector;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.NewRoundPayload;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.PreparedCertificate;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.ProposalPayload;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.RoundChangeCertificate;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.RoundChangePayload;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.SignedData;
import tech.pegasys.pantheon.consensus.ibft.validation.RoundChangeMessageValidator.MessageValidatorFactory;
import tech.pegasys.pantheon.ethereum.core.Address;

import java.util.Collection;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NewRoundMessageValidator {

  private static final Logger LOG = LogManager.getLogger();

  private final Collection<Address> validators;
  private final ProposerSelector proposerSelector;
  private final MessageValidatorFactory messageValidatorFactory;
  private final long quorumSize;
  private final long chainHeight;

  public NewRoundMessageValidator(
      final Collection<Address> validators,
      final ProposerSelector proposerSelector,
      final MessageValidatorFactory messageValidatorFactory,
      final long quorumSize,
      final long chainHeight) {
    this.validators = validators;
    this.proposerSelector = proposerSelector;
    this.messageValidatorFactory = messageValidatorFactory;
    this.quorumSize = quorumSize;
    this.chainHeight = chainHeight;
  }

  public boolean validateNewRoundMessage(final SignedData<NewRoundPayload> msg) {

    final NewRoundPayload payload = msg.getPayload();
    final ConsensusRoundIdentifier rootRoundIdentifier = payload.getRoundChangeIdentifier();
    final Address expectedProposer = proposerSelector.selectProposerForRound(rootRoundIdentifier);
    final RoundChangeCertificate roundChangeCert = payload.getRoundChangeCertificate();

    if (!expectedProposer.equals(msg.getSender())) {
      LOG.info("Invalid NewRound message, did not originate from expected proposer.");
      return false;
    }

    if (msg.getPayload().getRoundChangeIdentifier().getSequenceNumber() != chainHeight) {
      LOG.info("Invalid NewRound message, not valid for local chain height.");
      return false;
    }

    if (msg.getPayload().getRoundChangeIdentifier().getRoundNumber() == 0) {
      LOG.info("Invalid NewRound message, illegally targets a new round of 0.");
      return false;
    }

    final SignedData<ProposalPayload> proposalMessage = payload.getProposalPayload();

    if (!msg.getSender().equals(proposalMessage.getSender())) {
      LOG.info("Invalid NewRound message, embedded Proposal message not signed by sender.");
      return false;
    }

    if (!proposalMessage.getPayload().getRoundIdentifier().equals(rootRoundIdentifier)) {
      LOG.info("Invalid NewRound message, embedded Proposal has mismatched round.");
      return false;
    }

    if (!validateRoundChangeMessagesAndEnsureTargetRoundMatchesRoot(
        rootRoundIdentifier, roundChangeCert)) {
      return false;
    }

    return validateProposalMessageMatchesLatestPrepareCertificate(payload);
  }

  private boolean validateRoundChangeMessagesAndEnsureTargetRoundMatchesRoot(
      final ConsensusRoundIdentifier expectedRound, final RoundChangeCertificate roundChangeCert) {

    final Collection<SignedData<RoundChangePayload>> roundChangeMsgs =
        roundChangeCert.getRoundChangePayloads();

    if (roundChangeMsgs.size() < quorumSize) {
      LOG.info(
          "Invalid NewRound message, RoundChange certificate has insufficient "
              + "RoundChange messages.");
      return false;
    }

    if (!roundChangeCert
        .getRoundChangePayloads()
        .stream()
        .allMatch(p -> p.getPayload().getRoundChangeIdentifier().equals(expectedRound))) {
      LOG.info(
          "Invalid NewRound message, not all embedded RoundChange messages have a "
              + "matching target round.");
      return false;
    }

    for (final SignedData<RoundChangePayload> roundChangeMsg :
        roundChangeCert.getRoundChangePayloads()) {
      final RoundChangeMessageValidator roundChangeValidator =
          new RoundChangeMessageValidator(
              messageValidatorFactory, validators, quorumSize, chainHeight);

      if (!roundChangeValidator.validateMessage(roundChangeMsg)) {
        LOG.info("Invalid NewRound message, embedded RoundChange message failed validation.");
        return false;
      }
    }
    return true;
  }

  private boolean validateProposalMessageMatchesLatestPrepareCertificate(
      final NewRoundPayload payload) {

    final RoundChangeCertificate roundChangeCert = payload.getRoundChangeCertificate();
    final Collection<SignedData<RoundChangePayload>> roundChangeMsgs =
        roundChangeCert.getRoundChangePayloads();

    final Optional<PreparedCertificate> latestPreparedCertificate =
        findLatestPreparedCertificate(roundChangeMsgs);

    if (!latestPreparedCertificate.isPresent()) {
      LOG.info(
          "No round change messages have a preparedCertificate, any valid block may be proposed.");
      return true;
    }
    if (!latestPreparedCertificate
        .get()
        .getProposalPayload()
        .getPayload()
        .getBlock()
        .getHash()
        .equals(payload.getProposalPayload().getPayload().getBlock().getHash())) {
      LOG.info(
          "Invalid NewRound message, block in latest RoundChange does not match proposed block.");
      return false;
    }

    return true;
  }

  private Optional<PreparedCertificate> findLatestPreparedCertificate(
      final Collection<SignedData<RoundChangePayload>> msgs) {

    Optional<PreparedCertificate> result = Optional.empty();

    for (SignedData<RoundChangePayload> roundChangeMsg : msgs) {
      final RoundChangePayload payload = roundChangeMsg.getPayload();
      if (payload.getPreparedCertificate().isPresent()) {
        if (!result.isPresent()) {
          result = Optional.of(payload.getPreparedCertificate().get());
        } else {
          final PreparedCertificate currentLatest = result.get();
          final PreparedCertificate nextCert = payload.getPreparedCertificate().get();

          if (currentLatest.getProposalPayload().getPayload().getRoundIdentifier().getRoundNumber()
              < nextCert.getProposalPayload().getPayload().getRoundIdentifier().getRoundNumber()) {
            result = Optional.of(nextCert);
          }
        }
      }
    }
    return result;
  }
}
