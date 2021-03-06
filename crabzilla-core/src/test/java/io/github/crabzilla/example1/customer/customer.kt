package io.github.crabzilla.example1.customer

import io.github.crabzilla.framework.Command
import io.github.crabzilla.framework.DomainEvent
import java.time.Instant

typealias CustomerId = Int

// events

data class CustomerCreated(val customerId: CustomerId, val name: String) : DomainEvent

data class CustomerActivated(val reason: String, val _when: Instant) : DomainEvent

data class CustomerDeactivated(val reason: String, val _when: Instant) : DomainEvent

// commands

data class CreateCustomer(val name: String) : Command

data class ActivateCustomer(val reason: String) : Command

data class DeactivateCustomer(val reason: String) : Command

data class CreateActivateCustomer(val name: String, val reason: String) : Command

// just for test

data class UnknownCommand(val id: CustomerId) : Command

