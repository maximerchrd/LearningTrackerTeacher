CREATE TABLE koeko_collect.GEN_MUID(
	GEN_DATE date NOT NULL,
	MUID int NOT NULL,
   PRIMARY KEY (GEN_DATE),
UNIQUE INDEX `GEN_DATE_UNIQUE` (GEN_DATE ASC));

